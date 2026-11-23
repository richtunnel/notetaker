package com.notehive.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Produce/consume-side schema guard for envelopes. Strict about identity and
 * structure so consumers can safely evolve payloads (schemaVersion).
 */
public final class EventSchemaValidator {

    private EventSchemaValidator() {}

    public static void validate(EventEnvelope e) {
        if (e == null) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID, "Envelope missing");
        }
        if (e.eventId() == null || e.eventType() == null || e.eventType().isBlank()) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID, "Envelope identity missing");
        }
        if (e.tenantId() == null) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID, "Envelope tenant missing");
        }
        if (e.aggregateId() == null) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID, "Envelope aggregate missing");
        }
        if (e.occurredAt() == null || e.occurredAt().isAfter(Instant.now().plusSeconds(60))) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID, "Envelope occurredAt out of range");
        }
        if (e.schemaVersion() < 1 || e.schemaVersion() > 9) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID, "Unsupported schemaVersion");
        }
        JsonNode payload = e.payload();
        if (payload == null || !payload.isObject()) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID, "Payload must be a JSON object");
        }
    }
}
