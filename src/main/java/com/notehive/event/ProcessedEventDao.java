package com.notehive.event;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.UUID;

/**
 * Consumer-side duplicate suppression. Idempotency at the queue level: an
 * event id is accepted by a group exactly once (atomic upsert on the PK).
 */
@ApplicationScoped
public class ProcessedEventDao {

    @Inject
    EntityManager em;

    /** Atomic check-and-mark: INSERT ... ON CONFLICT DO NOTHING. */
    @Transactional
    public boolean markProcessedOnce(UUID eventId, String group, UUID orgId) {
        int inserted = em.createNativeQuery("""
                INSERT INTO processed_events (event_id, group_name, org_id, processed_at)
                VALUES (?1, ?2, ?3, now())
                ON CONFLICT (event_id, group_name) DO NOTHING
                """)
                .setParameter(1, eventId)
                .setParameter(2, group)
                .setParameter(3, orgId)
                .executeUpdate();
        return inserted == 1;
    }
}
