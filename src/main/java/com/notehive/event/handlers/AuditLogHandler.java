package com.notehive.event.handlers;

import com.notehive.common.EventEnvelope;
import com.notehive.common.Json;
import com.notehive.event.EventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/** Consumes ALL events into the audit trail (fan-out group, accepts everything). */
@ApplicationScoped
public class AuditLogHandler implements EventHandler {

    @Inject AuditWriter writer;
    @Inject MeterRegistry metrics;

    private Counter written;

    @PostConstruct
    void init() {
        written = Counter.builder("notehive_audit_written_total").register(metrics);
    }

    @Override
    public String group() {
        return "audit-logger";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(); // all
    }

    @Override
    public void handle(EventEnvelope event) {
        // TenantContext drives the @TenantBound interceptor inside the writer
        com.notehive.common.TenantContext.set(new com.notehive.common.TenantContext.Principal(
                null, event.tenantId(), null, null));
        try {
            writer.write(event.tenantId(), null, event.eventType(), "event",
                    event.aggregateId(), Json.write(event.payload()));
        } finally {
            com.notehive.common.TenantContext.clear();
        }
        written.increment();
    }
}
