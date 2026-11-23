package com.notehive.team;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Collaboration unit inside a tenant. RLS-bound via org_id. */
@Entity
@Table(name = "teams")
public class Team extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(nullable = false)
    public String name;

    @Column(name = "created_by", nullable = false)
    public UUID createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "archived_at")
    public Instant archivedAt;
}
