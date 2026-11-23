package com.notehive.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import com.notehive.common.TenantBound;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * PostgreSQL-backed idempotency records. The claim uses a native atomic upsert
 * (INSERT ... ON CONFLICT DO NOTHING) so concurrent callers with the same key
 * cannot double-execute; replay reads the stored response snapshot.
 */
@ApplicationScoped
public class IdempotencyStore {

    @Inject EntityManager em;

    public record Claim(boolean claimed, String requestHash, String status,
                        Integer responseCode, String responseBody, java.time.Instant createdAt) {}

    @Transactional
    @TenantBound
    public Claim claim(UUID orgId, String key, UUID userId, String method, String path,
                       String requestHash, int pendingSeconds) {
        int inserted = em.createNativeQuery("""
                INSERT INTO idempotency_records
                    (org_id, idempotency_key, user_id, method, path, request_hash, status, created_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'pending', now())
                ON CONFLICT (org_id, idempotency_key) DO NOTHING
                """)
                .setParameter(1, orgId)
                .setParameter(2, key)
                .setParameter(3, userId)
                .setParameter(4, method)
                .setParameter(5, path)
                .setParameter(6, requestHash)
                .executeUpdate();
        if (inserted == 1) {
            return new Claim(true, requestHash, "pending", null, null, Instant.now());
        }
        var rows = em.createNativeQuery("""
                SELECT request_hash, status, response_code, response_body, created_at
                FROM idempotency_records WHERE org_id = ?1 AND idempotency_key = ?2
                """)
                .setParameter(1, orgId)
                .setParameter(2, key)
                .getResultList();
        if (rows.isEmpty()) {
            return new Claim(false, requestHash, "pending", null, null, Instant.now());
        }
        Object[] row = (Object[]) rows.get(0);
        Instant createdAt = ((java.sql.Timestamp) row[4]).toInstant();
        // stale pending rows (crashed writer) can be taken over
        if ("pending".equals(row[1]) && createdAt.isBefore(Instant.now().minusSeconds(pendingSeconds))) {
            em.createNativeQuery("""
                    UPDATE idempotency_records SET request_hash = ?3, method = ?4, path = ?5,
                        user_id = ?6, created_at = now(), completed_at = NULL, response_code = NULL,
                        response_body = NULL
                    WHERE org_id = ?1 AND idempotency_key = ?2 AND status = 'pending'
                    """)
                    .setParameter(1, orgId).setParameter(2, key).setParameter(3, requestHash)
                    .setParameter(4, method).setParameter(5, path).setParameter(6, userId)
                    .executeUpdate();
            return new Claim(true, requestHash, "pending", null, null, Instant.now());
        }
        return new Claim(false, (String) row[0], (String) row[1],
                row[2] == null ? null : ((Number) row[2]).intValue(),
                (String) row[3], createdAt);
    }

    @Transactional
    @TenantBound
    public void complete(UUID orgId, String key, String requestHash, int responseCode, String responseBody) {
        em.createNativeQuery("""
                UPDATE idempotency_records
                   SET status = 'completed', response_code = ?3, response_body = ?4,
                       request_hash = ?5, completed_at = now()
                 WHERE org_id = ?1 AND idempotency_key = ?2 AND status = 'pending'
                """)
                .setParameter(1, orgId)
                .setParameter(2, key)
                .setParameter(3, responseCode)
                .setParameter(4, responseBody)
                .setParameter(5, requestHash)
                .executeUpdate();
    }

    /** Retention: rows older than the configured TTL (called by a periodic job). */
    @Transactional
    public int purgeOlderThan(Instant cutoff) {
        return em.createNativeQuery("DELETE FROM idempotency_records WHERE created_at < ?1")
                .setParameter(1, java.sql.Timestamp.from(cutoff))
                .executeUpdate();
    }
}
