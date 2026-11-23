package com.notehive.event.handlers;

import com.notehive.common.EventEnvelope;
import com.notehive.common.Json;
import com.notehive.event.EventHandler;
import com.notehive.infra.RedisConnectionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Cross-instance realtime fan-out: durable stream events are re-published to
 * Redis Pub/Sub channels that WebSocket gateways listen to. Streams give
 * durability + retries + DLQ; Pub/Sub carries only ephemeral realtime frames
 * (a missed frame is harmless — clients re-fetch on reconnect).
 */
@ApplicationScoped
public class RealtimeFanoutHandler implements EventHandler {

    public static final String TEAM_CHANNEL_PREFIX = "nh:team:";
    public static final String NOTE_CHANNEL_PREFIX = "nh:note:";

    @Inject RedisConnectionManager redis;

    @Override
    public String group() {
        return "realtime-fanout";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("note.created", "note.updated", "note.deleted", "note.restored",
                "comment.created", "team.created", "team.member_added", "team.member_removed");
    }

    @Override
    public void handle(EventEnvelope event) {
        String frame = Json.write(Json.objectNode()
                .put("type", event.eventType())
                .put("eventId", event.eventId().toString())
                .put("aggregateId", event.aggregateId().toString())
                .put("occurredAt", event.occurredAt().toString())
                .set("payload", event.payload()));
        String teamId = event.payload().path("teamId").asText(null);
        if (teamId != null && !teamId.isBlank()) {
            redis.commands().sync().publish(TEAM_CHANNEL_PREFIX + teamId, frame);
        }
        if (event.eventType().startsWith("note.")) {
            redis.commands().sync().publish(NOTE_CHANNEL_PREFIX + event.aggregateId(), frame);
        }
    }
}
