package com.notehive.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Tenant root: the organization IS the tenant. Auth-layer table (no RLS). */
@Entity
@Table(name = "organizations")
public class Organization extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(nullable = false)
    public String name;

    @Column(nullable = false)
    public String slug;

    @Column(name = "owner_email")
    public String ownerEmail;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public static boolean slugExists(String slug) {
        return Organization.count("slug", slug) > 0;
    }
}
