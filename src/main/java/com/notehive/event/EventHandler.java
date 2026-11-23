package com.notehive.event;

import com.notehive.common.EventEnvelope;

import java.util.Set;

/**
 * A durable event consumer. One consumer group per handler bean.
 *
 * Contract:
 *  - handlers MUST be idempotent: a crash between "effects committed" and
 *    "dedup marker written" can re-deliver once (processed_events covers the
 *    common case);
 *  - handlers commit their own database work (annotate internal services
 *    @Transactional); XACK happens only after handle() returns normally;
 *  - handlers that touch RLS-bound tables must bind the GUC for the event's
 *    tenant first (TenantBoundInterceptor runs inside their transaction when
 *    TenantContext is set);
 *  - throwing retries via the pending-entry claim path; after
 *    maxDeliveryAttempts the message goes to the DLQ.
 */
public interface EventHandler {

    /** Consumer group name (stable across deployments; never rename casually). */
    String group();

    /** Event types this handler processes; empty set = all (fan-out handlers). */
    Set<String> eventTypes();

    void handle(EventEnvelope event) throws Exception;

    default boolean accepts(String eventType) {
        Set<String> types = eventTypes();
        return types.isEmpty() || types.contains(eventType);
    }
}
