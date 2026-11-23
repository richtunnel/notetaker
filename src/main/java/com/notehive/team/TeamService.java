package com.notehive.team;

import com.notehive.auth.User;
import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.EventEnvelope;
import com.notehive.common.EventTypes;
import com.notehive.common.Json;
import com.notehive.common.TenantBound;
import com.notehive.event.EventPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

/**
 * Team lifecycle and authorization. Role ranking: owner > editor > viewer.
 * Membership checks are cached (version-fenced Redis) and invalidated by
 * membership-change events — see event.handlers.TeamCacheHandler.
 */
@ApplicationScoped
public class TeamService {

    @Inject EventPublisher events;
    @Inject EntityManager em;
    @Inject com.notehive.infra.RedisCacheStore cache;
    @Inject com.notehive.config.AppConfig.Cache cacheCfg;
    @Inject RoleLoader roleLoader;

    public record TeamView(UUID id, String name, String createdAt, String myRole) {}

    @Transactional
    public TeamView create(UUID tenantId, UUID userId, String name) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "name required (max 80)");
        }
        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.id = teamId;
        team.orgId = tenantId;
        team.name = name.trim();
        team.createdBy = userId;
        team.persist();

        TeamMember owner = new TeamMember();
        owner.id = UUID.randomUUID();
        owner.orgId = tenantId;
        owner.teamId = teamId;
        owner.userId = userId;
        owner.role = "owner";
        owner.addedBy = userId;
        owner.persist();

        events.publish(EventEnvelope.create(EventTypes.TEAM_CREATED, tenantId, teamId, null,
                Json.objectNode().put("name", team.name).put("teamId", teamId.toString())));
        return new TeamView(teamId, team.name, team.createdAt.toString(), "owner");
    }

    @Transactional
    public void addMember(UUID tenantId, UUID actorId, UUID teamId,
                          String username, String role) {
        requireRole(tenantId, actorId, teamId, "editor");
        if (!Set.of("editor", "viewer", "owner").contains(role == null ? "" : role)) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED,
                    "role must be owner|editor|viewer");
        }
        User user = User.findByUsernameLower(username)
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.USER_NOT_FOUND,
                        "No such user on this platform"));
        if (user.orgId != tenantId) {
            // users live in one org; cross-org sharing is rejected up front
            throw new ApiException.Forbidden("User belongs to another organization");
        }
        if (TeamMember.findMembership(teamId, user.id).isPresent()) {
            throw new ApiException.Conflict(ErrorCode.VALIDATION_FAILED, "Already a member");
        }
        TeamMember member = new TeamMember();
        member.id = UUID.randomUUID();
        member.orgId = tenantId;
        member.teamId = teamId;
        member.userId = user.id;
        member.role = role;
        member.addedBy = actorId;
        member.persist();

        events.publish(EventEnvelope.create(EventTypes.TEAM_MEMBER_ADDED, tenantId, teamId, null,
                Json.objectNode()
                        .put("userId", user.id.toString())
                        .put("role", role)));
    }

    @Transactional
    public void removeMember(UUID tenantId, UUID actorId, UUID teamId, UUID targetUserId) {
        requireRole(tenantId, actorId, teamId, "editor");
        TeamMember member = TeamMember.findMembership(teamId, targetUserId)
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.FORBIDDEN, "Not a member"));
        if ("owner".equals(member.role)) {
            throw new ApiException.Forbidden("Team owner cannot be removed");
        }
        member.delete();
        events.publish(EventEnvelope.create(EventTypes.TEAM_MEMBER_REMOVED, tenantId, teamId, null,
                Json.objectNode().put("userId", targetUserId.toString())));
    }

    /** N+1-free listing: ONE query joins team + membership + member count. */
    @Transactional
    @TenantBound
    @SuppressWarnings("unchecked")
    public List<TeamView> listForUser(UUID tenantId, UUID userId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT t.id, t.name, t.created_at, tm.role,
                       (SELECT count(*) FROM team_members m2 WHERE m2.team_id = t.id) AS member_count
                FROM team_members tm
                JOIN teams t ON t.id = tm.team_id
                WHERE tm.org_id = ?1 AND tm.user_id = ?2 AND t.archived_at IS NULL
                ORDER BY t.created_at DESC
                """)
                .setParameter(1, tenantId)
                .setParameter(2, userId)
                .getResultList();
        return rows.stream().map(r -> new TeamView(
                (UUID) r[0], (String) r[1], r[2].toString(), (String) r[3])).toList();
    }

    public Optional<String> roleInTeam(UUID tenantId, UUID userId, UUID teamId) {
        String key = teamId + ":" + userId;
        return Optional.ofNullable(cache.getOrLoad("teamrole", tenantId.toString(), key,
                String.class, cacheCfg.membership(),
                () -> roleLoader.load(tenantId, teamId, userId)));
    }

    /** RLS-safe membership lookup: binds the GUC in its own transaction, so it
     *  works both inside request transactions and on WebSocket threads. */
    @ApplicationScoped
    public static class RoleLoader {
        @Inject EntityManager em;

        @Transactional
        public String load(UUID tenantId, UUID teamId, UUID userId) {
            em.createNativeQuery("SELECT set_config('app.tenant_id', ?1, true)")
                    .setParameter(1, tenantId.toString())
                    .getSingleResult();
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT role FROM team_members WHERE team_id = ?1 AND user_id = ?2 " +
                            "AND org_id = current_setting('app.tenant_id', true)::uuid")
                    .setParameter(1, teamId)
                    .setParameter(2, userId)
                    .getResultList();
            return rows.isEmpty() ? null : (String) rows.get(0)[0];
        }
    }

    public void requireRole(UUID tenantId, UUID userId, UUID teamId, String minimum) {
        String role = roleInTeam(tenantId, userId, teamId).orElse(null);
        if (role == null || rank(role) < rank(minimum)) {
            throw new ApiException.Forbidden("Requires " + minimum + " role in the team");
        }
    }

    public void requireMember(UUID tenantId, UUID userId, UUID teamId) {
        if (roleInTeam(tenantId, userId, teamId).isEmpty()) {
            throw new ApiException.Forbidden("Not a member of this team");
        }
    }

    public static int rank(String role) {
        return switch (role == null ? "" : role) {
            case "owner" -> 3;
            case "editor" -> 2;
            case "viewer" -> 1;
            default -> 0;
        };
    }
}
