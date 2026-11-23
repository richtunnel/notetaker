package com.notehive.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Application user. Auth-layer table (no RLS; login precedes tenant resolution). */
@Entity
@Table(name = "users")
public class User extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(nullable = false)
    public String username;

    @Column(nullable = false)
    public String email;

    @Column(name = "display_name", nullable = false)
    public String displayName;

    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    @Column(nullable = false)
    public String status = "active";

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    public static Optional<User> findByEmailLower(String email) {
        return User.<User>find("lower(email)", email == null ? "" : email.trim().toLowerCase())
                .firstResultOptional();
    }

    public static Optional<User> findByUsernameLower(String username) {
        return User.<User>find("lower(username)", username == null ? "" : username.trim().toLowerCase())
                .firstResultOptional();
    }
}
