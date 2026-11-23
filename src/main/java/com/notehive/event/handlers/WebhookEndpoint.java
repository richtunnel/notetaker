package com.notehive.event.handlers;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Outbound webhook registration (secrets stored AES-GCM encrypted). */
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(nullable = false, columnDefinition = "varchar(500)")
    public String url;

    @Column(name = "secret_enc", nullable = false, columnDefinition = "bytea")
    public byte[] secretEnc;

    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    public String[] eventTypes = new String[0]; // empty = all events

    @Column(nullable = false)
    public boolean active = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
