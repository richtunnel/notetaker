package com.notehive.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared Jackson mapper for the event backbone and cache payloads. REST
 * serialization keeps using Quarkus' configured mapper — this one is strictly
 * for internal contracts so we control evolution independently.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {}

    public static ObjectNode objectNode() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID,
                    "Malformed JSON: " + e.getMessage());
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new ApiException.Internal("JSON serialization failed", e);
        }
    }

    public static <T> T read(String raw, Class<T> type) {
        try {
            return MAPPER.readValue(raw, type);
        } catch (Exception e) {
            throw new ApiException.Validation(ErrorCode.EVENT_INVALID,
                    "Malformed JSON: " + e.getMessage());
        }
    }
}
