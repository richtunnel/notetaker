package com.notehive.event.handlers;

import com.notehive.common.EventEnvelope;
import com.notehive.event.EventHandler;
import com.notehive.infra.RedisCacheStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Cache invalidation driven by events (write-through invalidation without
 * touching the request path): note mutations bump the version-fenced note
 * cache; membership changes bump the team-role cache. The version fence makes
 * this safe against repopulation races.
 */
@ApplicationScoped
public class CacheEvictorHandler implements EventHandler {

    @Inject RedisCacheStore cache;

    @Override
    public String group() {
        return "cache-evictor";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("note.created", "note.updated", "note.deleted", "note.restored",
                "team.member_added", "team.member_removed");
    }

    @Override
    public void handle(EventEnvelope event) {
        switch (event.eventType()) {
            case "note.created", "note.updated", "note.deleted", "note.restored" ->
                    cache.invalidate("note", event.tenantId().toString(),
                            event.aggregateId().toString());
            case "team.member_added", "team.member_removed" -> {
                String userId = event.payload().path("userId").asText(null);
                if (userId != null) {
                    cache.invalidate("teamrole", event.tenantId().toString(),
                            event.aggregateId() + ":" + userId);
                }
            }
            default -> { }
        }
    }
}
