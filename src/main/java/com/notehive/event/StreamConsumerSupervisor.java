package com.notehive.event;

import com.notehive.config.AppConfig;
import com.notehive.infra.RedisConnectionManager;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires one StreamConsumerRunner per EventHandler bean (queue consumers run
 * in parallel groups). Graceful shutdown: stop fetching, drain, leave unacked
 * entries in the PEL.
 */
@ApplicationScoped
public class StreamConsumerSupervisor {

    private static final Logger LOG = LoggerFactory.getLogger(StreamConsumerSupervisor.class);

    @Inject AppConfig.Events config;
    @Inject RedisConnectionManager redis;
    @Inject ProcessedEventDao dedup;
    @Inject Instance<EventHandler> handlers;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;

    private final List<Thread> threads = new ArrayList<>();
    private final List<StreamConsumerRunner> runners = new ArrayList<>();

    void start(@Observes StartupEvent ev) {
        for (EventHandler handler : handlers) {
            var runner = new StreamConsumerRunner(handler, config,
                    redis.commands(), dedup, metrics);
            runners.add(runner);
            Thread t = new Thread(runner, "consumer-" + handler.group());
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        LOG.info("Started {} stream consumer groups", runners.size());
    }

    void stop(@Observes ShutdownEvent ev) {
        runners.forEach(StreamConsumerRunner::shutdown);
        for (Thread t : threads) {
            try {
                t.join(35_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
