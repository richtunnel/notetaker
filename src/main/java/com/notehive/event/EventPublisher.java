package com.notehive.event;

import com.notehive.common.EventEnvelope;

/**
 * The only way business code publishes durable events: writes an outbox row
 * INSIDE the caller's transaction (transactional outbox). Redis publishing
 * happens later, asynchronously, by the OutboxRelay — so the business commit
 * and the event are atomic: no lost events, no phantom events.
 */
public interface EventPublisher {

    /**
     * Must be called within an active database transaction that also mutates
     * the business records — same-commit guarantee.
     */
    void publish(EventEnvelope envelope);
}
