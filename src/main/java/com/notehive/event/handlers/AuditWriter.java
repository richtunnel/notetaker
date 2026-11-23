package com.notehive.event.handlers;

import com.notehive.common.ApiException;
import com.notehive.common.TenantBound;
import com.notehive.common.ErrorCode;
import com.notehive.common.EventEnvelope;
import com.notehive.event.EventHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.UUID;

/**
 * Append-only audit trail. The insert is RLS-bound: the writer binds the GUC
 * inside its own transaction (TenantContext drives the interceptor).
 */
@ApplicationScoped
public class AuditWriter {

    @Inject EntityManager em;

    @Transactional
    @TenantBound
    public void write(UUID orgId, UUID actorId, String action, String targetType,
                      UUID targetId, String detailJson) {
        {
            em.createNativeQuery("""
                    INSERT INTO audit_logs (org_id, actor_id, action, target_type, target_id, detail, occurred_at)
                    VALUES (?1, ?2, ?3, ?4, ?5, CAST(?6 AS jsonb), now())
                    """)
                    .setParameter(1, orgId)
                    .setParameter(2, actorId)
                    .setParameter(3, action)
                    .setParameter(4, targetType)
                    .setParameter(5, targetId)
                    .setParameter(6, detailJson)
                    .executeUpdate();
        }
    }
}
