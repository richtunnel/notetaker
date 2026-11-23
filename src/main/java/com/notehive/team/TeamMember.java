package com.notehive.team;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Membership row: the single source of truth for team authorization. */
@Entity
@Table(name = "team_members")
public class TeamMember extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(name = "team_id", nullable = false)
    public UUID teamId;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    /** owner | editor | viewer */
    @Column(nullable = false)
    public String role;

    @Column(name = "added_by")
    public UUID addedBy;

    @Column(name = "joined_at", nullable = false)
    public Instant joinedAt = Instant.now();

    /** Explicit type witness: chained find() needs it (raw-type inference otherwise). */
    public static Optional<TeamMember> findMembership(UUID teamId, UUID userId) {
        return TeamMember.<TeamMember>find("teamId = ?1 and userId = ?2", teamId, userId)
                .firstResultOptional();
    }

    public static List<TeamMember> listByUser(UUID orgId, UUID userId) {
        return TeamMember.list("orgId = ?1 and userId = ?2", orgId, userId);
    }

    public static List<TeamMember> listByTeam(UUID teamId) {
        return TeamMember.list("teamId", teamId);
    }
}
