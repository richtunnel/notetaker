package com.notehive.event;

import com.notehive.config.AppConfig;
import com.notehive.infra.RedisConnectionManager;
import io.lettuce.core.XAddArgs;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Transactional-outbox relay: polls the outbox and XADDs envelopes to the
 * Redis Stream.
 *
 * Guarantees (spec: at-least-once end-to-end):
 *  - XADD happens strictly BEFORE the row is marked published — a crash
 *    between the two leaves the row pending and it is re-published; consumers
 *    dedup by eventId (processed_events);
 *  - failures: bounded exponential backoff with jitter; DLQ after
 *    maxPublishAttempts; dead rows are alerted on, never retried forever.
 */
@ApplicationScoped
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    @Inject AppConfig.Events config;
    @Inject RedisConnectionManager redis;
    @Inject OutboxTx tx;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;

    private io.micrometer.core.instrument.Counter publishedCounter;
    private io.micrometer.core.instrument.Counter deadCounter;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    void start(@Observes StartupEvent ev) {
        publishedCounter = io.micrometer.core.instrument.Counter
                .builder("notehive_outbox_published_total").register(metrics);
        deadCounter = io.micrometer.core.instrument.Counter
                .builder("notehive_outbox_dead_total").register(metrics);
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::pollOnce, 500,
                config.relayPollInterval().toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("OutboxRelay started (stream={}, batch={})", config.stream(), config.relayBatchSize());
    }

    void stop(@Observes ShutdownEvent ev) {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    void pollOnce() {
        if (!running) {
            return;
        }
        try {
            List<OutboxEvent> due = tx.findDue(config.relayBatchSize());
            for (OutboxEvent row : due) {
                publishRow(row);
            }
        } catch (Exception e) {
            LOG.warn("Outbox poll failed (retry next tick): {}", e.getMessage());
        }
    }

    private void publishRow(OutboxEvent row) {
        try {
            String body = com.notehive.common.Json.write(row.toEnvelope());
            redis.commands().sync().xadd(config.stream(),
                    XAddArgs.Builder.maxlen(config.maxlen()).approximateTrimming(true),
                    Map.of("data", body));
            tx.markPublished(row.id);
            publishedCounter.increment();
        } catch (Exception publishError) {
            failRow(row, publishError);
        }
    }

    private void failRow(OutboxEvent row, Throwable cause) {
        int attempts = row.attempts + 1;
        if (attempts >= config.maxPublishAttempts()) {
            LOG.error("Outbox event {} dead after {} attempts: {}",
                    row.eventId, attempts, cause.getMessage());
            try {
                redis.commands().sync().xadd(config.dlqStream(),
                        XAddArgs.Builder.maxlen(100_000).approximateTrimming(true),
                        Map.of("data", com.notehive.common.Json.write(row.toEnvelope()),
                                "error", String.valueOf(cause.getMessage())));
            } catch (Exception dlqError) {
                LOG.error("Failed to write DLQ entry for {}", row.eventId, dlqError);
            }
            tx.markDead(row.id, attempts);
            deadCounter.increment();
            return;
        }
        long backoff = Backoff.nextDelayMillis(attempts,
                config.backoffBase().toMillis(), 30_000);
        Instant next = Instant.now().plusMillis(backoff);
        LOG.warn("Outbox publish attempt {} failed for event {} ({}): {}; retry at {}",
                attempts, row.eventId, row.eventType, cause.getMessage(), next);
        tx.markFailed(row.id, attempts, next);
    }
}
