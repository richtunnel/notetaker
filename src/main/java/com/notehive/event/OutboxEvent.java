package com.notehive.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.notehive.common.EventEnvelope;
import com.notehive.common.EventSchemaValidator;
import com.notehive.common.Json;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Outbox row. Payload stored as jsonb. Not RLS-bound: the relay is infra. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends PanacheEntityBase {

    // id generation is bound to the schema's bigserial sequence explicitly.
    // (Extending PanacheEntity + its bare @GeneratedValue would resolve to
    // Hibernate 6's AUTO default sequence "<table>_SEQ" — never created,
    // because schema generation is 'none' and Flyway is authoritative.)
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_events_id_gen")
    @SequenceGenerator(name = "outbox_events_id_gen", sequenceName = "outbox_events_id_seq",
            allocationSize = 1)
    @Column(name = "id")
    public Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    public UUID eventId;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "schema_version", nullable = false)
    public int schemaVersion;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(name = "aggregate_id", nullable = false)
    public UUID aggregateId;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;

    @Column(name = "correlation_id")
    public UUID correlationId;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    public String payload;

    @Column(nullable = false)
    public String status = "pending";

    @Column(nullable = false)
    public int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    public Instant nextAttemptAt = Instant.now();

    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public static OutboxEvent from(EventEnvelope envelope) {
        EventSchemaValidator.validate(envelope);
        OutboxEvent row = new OutboxEvent();
        row.eventId = envelope.eventId();
        row.eventType = envelope.eventType();
        row.schemaVersion = envelope.schemaVersion();
        row.orgId = envelope.tenantId();
        row.aggregateId = envelope.aggregateId();
        row.occurredAt = envelope.occurredAt();
        row.correlationId = envelope.correlationId();
        row.payload = Json.MAPPER.valueToTree(envelope.payload()).toString();
        return row;
    }

    public EventEnvelope toEnvelope() {
        JsonNode payload;
        try {
            payload = Json.MAPPER.readTree(this.payload);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupted outbox payload " + eventId, e);
        }
        return new EventEnvelope(eventId, eventType, schemaVersion, orgId,
                aggregateId, occurredAt, correlationId, payload);
    }

    /** Transactional outbox writer (the EventPublisher impl). MANDATORY: a
     *  call outside a transaction is a programming error, not a silent async. */
    @ApplicationScoped
    public static class Writer implements EventPublisher {

        @Inject
        EntityManager em;

        @Override
        @Transactional(jakarta.transaction.Transactional.TxType.MANDATORY)
        public void publish(EventEnvelope envelope) {
            em.persist(OutboxEvent.from(envelope));
        }
    }
}
