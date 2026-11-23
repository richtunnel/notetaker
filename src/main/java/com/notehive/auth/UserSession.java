package com.notehive.auth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-enforced session lifecycle:
 *  - refresh tokens are opaque; only sha256(refresh) is stored (refresh_hash);
 *  - rotation: every /refresh issues a new token in the same family_id;
 *  - reuse detection: presenting an already-rotated refresh token revokes the
 *    whole family (stolen-token containment);
 *  - idle window extended on use; absolute bound (24h / 72h remember-me)
 *    never moves — no silent extension.
 */
@Entity
@Table(name = "sessions")
public class UserSession extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(name = "family_id", nullable = false)
    public UUID familyId;

    @Column(name = "refresh_hash", nullable = false, unique = true)
    public String refreshHash;

    @Column(name = "idle_expires_at", nullable = false)
    public Instant idleExpiresAt;

    @Column(name = "absolute_expires_at", nullable = false)
    public Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Column(name = "revoked_reason")
    public String revokedReason;

    @Column(name = "user_agent")
    public String userAgent;

    @Column(name = "ip")
    public String ip;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "last_used_at", nullable = false)
    public Instant lastUsedAt = Instant.now();

    public static Optional<UserSession> findActiveByRefreshHash(String refreshHash, Instant now) {
        return UserSession.<UserSession>find(
                        "refreshHash = ?1 and revokedAt is null and idleExpiresAt > ?2 and absoluteExpiresAt > ?2",
                        refreshHash, now)
                .firstResultOptional();
    }

    public static int revokeFamily(UUID familyId, String reason, Instant now) {
        return UserSession.update(
                "revokedAt = ?1, revokedReason = ?2 where familyId = ?3 and revokedAt is null",
                now, reason, familyId);
    }

    public static int revokeAllForUser(UUID userId, String reason, Instant now) {
        return UserSession.update(
                "revokedAt = ?1, revokedReason = ?2 where userId = ?3 and revokedAt is null",
                now, reason, userId);
    }
}
