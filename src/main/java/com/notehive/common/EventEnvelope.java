package com.notehive.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned event envelope carried on Redis Streams (see docs/ARCHITECTURE.md).
 * Immutable record; validated by {@link EventSchemaValidator} at produce and
 * consume time. Payload is schema-version-specific JSON.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID tenantId,
        UUID aggregateId,
        Instant occurredAt,
        UUID correlationId,
        JsonNode payload) {

    public static EventEnvelope create(
            String eventType, UUID tenantId, UUID aggregateId,
            UUID correlationId, JsonNode payload) {
        return new EventEnvelope(
                UUID.randomUUID(), eventType, 1, tenantId, aggregateId,
                Instant.now(), correlationId, payload);
    }
}
