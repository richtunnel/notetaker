package com.notehive.event;

import com.notehive.common.EventEnvelope;
import com.notehive.common.EventSchemaValidator;
import com.notehive.common.Json;
import com.notehive.config.AppConfig;
import com.notehive.infra.RedisConnectionManager;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.PendingMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One runner per EventHandler bean: XREADGROUP loop on a dedicated thread,
 * bounded worker pool (parallel processing with backpressure — reads pause
 * above maxInFlight), XACK strictly after successful processing, duplicate
 * suppression via processed_events, pending-entry recovery (XPENDING + XCLAIM)
 * and DLQ after maxDeliveryAttempts. Graceful shutdown: stop fetching, drain
 * in-flight work, leave unacked entries in the PEL for recovery — nothing lost.
 */
final class StreamConsumerRunner implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(StreamConsumerRunner.class);

    private final EventHandler handler;
    private final AppConfig.Events config;
    private final StatefulRedisConnection<String, String> redis;
    private final ProcessedEventDao dedup;
    private final String consumerId;
    private final ExecutorService pool;
    private final Counter consumed;
    private final Counter duplicates;
    private final Counter failures;
    private final Counter dead;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean running = true;
    private long lastRecovery = 0;

    StreamConsumerRunner(EventHandler handler, AppConfig.Events config,
                         StatefulRedisConnection<String, String> redis,
                         ProcessedEventDao dedup, MeterRegistry metrics) {
        this.handler = handler;
        this.config = config;
        this.redis = redis;
        this.dedup = dedup;
        this.consumerId = config.consumerId().isBlank()
                ? "c-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
                : config.consumerId();
        this.pool = Executors.newFixedThreadPool(Math.max(1, config.handlerConcurrency()), r -> {
            Thread t = new Thread(r, "stream-" + handler.group());
            t.setDaemon(true);
            return t;
        });
        this.consumed = Counter.builder("notehive_events_consumed_total")
                .tag("group", handler.group()).register(metrics);
        this.duplicates = Counter.builder("notehive_events_duplicates_total")
                .tag("group", handler.group()).register(metrics);
        this.failures = Counter.builder("notehive_events_failures_total")
                .tag("group", handler.group()).register(metrics);
        this.dead = Counter.builder("notehive_events_dlq_total")
                .tag("group", handler.group()).register(metrics);
        Gauge.builder("notehive_events_pending", pendingGauge, AtomicLong::doubleValue)
                .tag("group", handler.group()).register(metrics);
    }

    String group() { return handler.group(); }

    void shutdown() {
        running = false;
    }

    @Override
    public void run() {
        LOG.info("Stream consumer starting group={} events={}", handler.group(), handler.eventTypes());
        try {
            redis.sync().xgroupCreate(
                    XReadArgs.StreamOffset.from(config.stream(), "0-"),
                    handler.group(),
                    XGroupCreateArgs.Builder.mkstream());
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                LOG.error("xgroup create failed for {}", handler.group(), e);
            }
        }
        while (running) {
            try {
                loop();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOG.warn("Consumer {} loop error: {}", handler.group(), e.getMessage());
                sleepQuietly(1000);
            }
        }
        drain();
    }

    private void loop() throws InterruptedException {
        if (inFlight.get() >= config.maxInFlight()) {
            Thread.sleep(50); // backpressure: do not read while overloaded
            maybeRecover();
            return;
        }
        List<StreamMessage<String, String>> messages = redis.sync().xreadgroup(
                Consumer.from(handler.group(), consumerId),
                XReadArgs.Builder.count(config.consumerBatchSize())
                        .block(Duration.ofMillis(config.pollBlockTime().toMillis())),
                XReadArgs.StreamOffset.lastConsumed(config.stream()));
        if (messages == null || messages.isEmpty()) {
            retryOwnPending();
            maybeRecover();
            return;
        }
        for (StreamMessage<String, String> m : messages) {
            inFlight.incrementAndGet();
            pool.execute(() -> {
                try {
                    process(m);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /** Read our own PEL ("0") to finish work claimed or interrupted earlier. */
    private void retryOwnPending() {
        try {
            List<StreamMessage<String, String>> pending = redis.sync().xreadgroup(
                    Consumer.from(handler.group(), consumerId),
                    XReadArgs.Builder.count(config.consumerBatchSize()).block(Duration.ZERO),
                    XReadArgs.StreamOffset.from(config.stream(), "0"));
            if (pending != null) {
                for (StreamMessage<String, String> m : pending) {
                    inFlight.incrementAndGet();
                    pool.execute(() -> {
                        try { process(m); } finally { inFlight.decrementAndGet(); }
                    });
                }
            }
        } catch (Exception e) {
            LOG.debug("PEL retry skipped: {}", e.getMessage());
        }
    }

    /** Claim stale entries from crashed consumers; DLQ those past max attempts. */
    private void maybeRecover() {
        long now = System.currentTimeMillis();
        if (now - lastRecovery < 30_000) return;
        lastRecovery = now;
        try {
            List<PendingMessage> pending = redis.sync().xpending(
                    config.stream(), handler.group(),
                    Range.from(Range.Boundary.including("-"), Range.Boundary.including("+")),
                    Limit.from(100));
            pendingGauge.set(redis.sync().xpending(config.stream(), handler.group()).getCount());
            for (PendingMessage p : pending) {
                boolean exhausted = p.getRedeliveryCount() >= config.maxDeliveryAttempts();
                boolean idle = p.getMsSinceLastDelivery() >= config.claimMinIdle().toMillis();
                if (!idle) continue;
                Consumer<String> self = Consumer.from(handler.group(), consumerId);
                if (exhausted) {
                    List<StreamMessage<String, String>> claimed =
                            redis.sync().xclaim(config.stream(), self,
                                    config.claimMinIdle().toMillis(), p.getId());
                    for (StreamMessage<String, String> m : claimed) {
                        redis.sync().xadd(config.dlqStream(),
                                XAddArgs.Builder.maxlen(100_000).approximateTrimming(true),
                                Map.of("data", m.getBody().getOrDefault("data", ""),
                                        "group", handler.group(),
                                        "reason", "max delivery attempts exceeded"));
                        redis.sync().xack(config.stream(), handler.group(), m.getId());
                        dead.increment();
                        LOG.error("Event dead-lettered by group {}: {}", handler.group(), m.getId());
                    }
                } else {
                    redis.sync().xclaim(config.stream(), self,
                            config.claimMinIdle().toMillis(), p.getId());
                }
            }
        } catch (Exception e) {
            LOG.debug("Pending recovery skipped: {}", e.getMessage());
        }
    }

    private void process(StreamMessage<String, String> m) {
        String body = m.getBody().get("data");
        if (body == null) {
            LOG.warn("Malformed stream entry {} (no data field) — acked to skip", m.getId());
            ack(m.getId());
            return;
        }
        EventEnvelope envelope;
        try {
            envelope = Json.read(body, EventEnvelope.class);
            EventSchemaValidator.validate(envelope);
        } catch (Exception e) {
            LOG.error("Invalid envelope {} in stream — acked to skip: {}", m.getId(), e.getMessage());
            ack(m.getId());
            return;
        }
        if (!handler.accepts(envelope.eventType())) {
            ack(m.getId());
            return;
        }
        try {
            if (!dedup.markProcessedOnce(envelope.eventId(), handler.group(), envelope.tenantId())) {
                duplicates.increment();
                ack(m.getId());
                return;
            }
            handler.handle(envelope);
            consumed.increment();
            ack(m.getId());
        } catch (Exception e) {
            failures.increment();
            LOG.warn("Handler {} failed for event {} (retried via PEL): {}",
                    handler.group(), envelope.eventId(), e.getMessage());
        }
    }

    private void ack(String id) {
        try {
            redis.sync().xack(config.stream(), handler.group(), id);
        } catch (Exception e) {
            LOG.warn("XACK failed for {} — will be re-acked on redelivery", id);
        }
    }

    private void drain() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.warn("Consumer {} did not drain within 30s; unacked entries stay in PEL",
                        handler.group());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
