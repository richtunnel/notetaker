package com.notehive.event;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Outbox state transitions. Separate bean so relay-thread calls cross a CDI
 * interceptor boundary (self-invocation would silently skip @Transactional —
 * the transaction-wrapper pitfall).
 */
@ApplicationScoped
public class OutboxTx {

    @Transactional
    public List<OutboxEvent> findDue(int batchSize) {
        PanacheQuery<OutboxEvent> q = OutboxEvent.find(
                "status in ('pending','failed') and nextAttemptAt <= ?1 order by id asc",
                Instant.now());
        q.page(0, batchSize);
        return q.list();
    }

    @Transactional
    public void markPublished(Long id) {
        OutboxEvent.update("status='published', publishedAt=?1 where id=?2", Instant.now(), id);
    }

    @Transactional
    public void markFailed(Long id, int attempts, Instant nextAttemptAt) {
        OutboxEvent.update("status='failed', attempts=?1, nextAttemptAt=?2 where id=?3",
                attempts, nextAttemptAt, id);
    }

    @Transactional
    public void markDead(Long id, int attempts) {
        OutboxEvent.update("status='dead', attempts=?1 where id=?2", attempts, id);
    }
}
