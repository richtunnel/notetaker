package com.notehive.auth;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.EventEnvelope;
import com.notehive.common.EventTypes;
import com.notehive.common.Json;
import com.notehive.config.AppConfig;
import com.notehive.event.EventPublisher;
import com.notehive.team.Team;
import com.notehive.team.TeamMember;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Registration, login, refresh-token rotation with reuse detection, logout.
 * All mutations run inside transactions that ALSO write outbox events
 * (same-commit guarantee — see EventPublisher). Tenant GUC is bound manually
 * during registration because the tenant (new organization) is being born in
 * that same transaction; other flows resolve the tenant from the user.
 */
@ApplicationScoped
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject PasswordHasher passwords;
    @Inject AppConfig.Session sessionCfg;
    @Inject JwtService jwt;
    @Inject EventPublisher events;
    @Inject EntityManager em;

    public record TokenPair(String accessToken, String refreshToken,
                            long accessTtlSeconds, UUID sessionId) {}

    // ------------------------------------------------------------------ register
    @Transactional
    public TokenPair register(String orgName, String username, String email, String password,
                              boolean rememberMe, String userAgent, String ip) {
        UUID orgId = UUID.randomUUID();
        // RLS bootstrap: bind the GUC to the org being born in this transaction
        bindGuc(orgId);

        Organization org = new Organization();
        org.id = orgId;
        org.name = orgName.trim();
        org.slug = uniqueSlug(orgName);
        org.ownerEmail = email.trim().toLowerCase();
        org.persist();

        User user = new User();
        user.id = UUID.randomUUID();
        user.orgId = orgId;
        user.username = username.trim();
        user.email = email.trim().toLowerCase();
        user.displayName = username.trim();
        user.passwordHash = passwords.hash(password);
        user.persist();

        Team general = new Team();
        general.id = UUID.randomUUID();
        general.orgId = orgId;
        general.name = "General";
        general.createdBy = user.id;
        general.persist();

        TeamMember member = new TeamMember();
        member.id = UUID.randomUUID();
        member.orgId = orgId;
        member.teamId = general.id;
        member.userId = user.id;
        member.role = "owner";
        member.addedBy = user.id;
        member.persist();

        events.publish(EventEnvelope.create(EventTypes.USER_REGISTERED, orgId, user.id, null,
                Json.objectNode()
                        .put("username", user.username)
                        .put("email", user.email)));

        return issueTokens(user, rememberMe, userAgent, ip);
    }

    // ------------------------------------------------------------------ login
    @Transactional
    public TokenPair login(String emailOrUsername, String password, boolean rememberMe,
                           String userAgent, String ip) {
        String normalized = emailOrUsername == null ? "" : emailOrUsername.trim().toLowerCase();
        User user = User.findByEmailLower(normalized).or(() -> User.findByUsernameLower(normalized))
                .orElseThrow(() -> new ApiException.Unauthorized(ErrorCode.INVALID_CREDENTIALS,
                        "Invalid credentials"));
        if (!"active".equals(user.status) || !passwords.verify(password, user.passwordHash)) {
            // uniform error: never disclose which factor failed
            throw new ApiException.Unauthorized(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");
        }
        bindGuc(user.orgId);
        return issueTokens(user, rememberMe, userAgent, ip);
    }

    // ------------------------------------------------------------------ refresh (rotation + reuse detection)
    @Transactional
    public TokenPair refresh(String refreshToken, String userAgent, String ip) {
        String hash = com.notehive.common.CursorCodec.sha256Hex(refreshToken.getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        Instant now = Instant.now();
        UserSession session = UserSession.findActiveByRefreshHash(hash, now)
                .orElseThrow(() -> new ApiException.Unauthorized(ErrorCode.REFRESH_REPLAY,
                        "Refresh token invalid"));

        // reuse detection: this hash must not have been rotated away already
        boolean replay = UserSession.count(
                "familyId = ?1 and refreshHash = ?2 and revokedReason = 'rotated'",
                session.familyId, hash) > 0;
        if (replay) {
            UserSession.revokeFamily(session.familyId, "refresh-replay", now);
            throw new ApiException.Unauthorized(ErrorCode.REFRESH_REPLAY,
                    "Refresh token reuse detected — session family revoked");
        }

        // rotate: retire this row, mint a sibling in the same family with the
        // SAME absolute bound (never extended) and a fresh idle window
        session.revokedAt = now;
        session.revokedReason = "rotated";

        User user = User.findById(session.userId);
        if (user == null || !"active".equals(user.status)) {
            throw new ApiException.Unauthorized(ErrorCode.SESSION_INVALID, "User is not active");
        }
        bindGuc(user.orgId);
        return issueTokens(user, session.absoluteExpiresAt, session.familyId, userAgent, ip);
    }

    @Transactional
    public void logout(String refreshToken, String reason) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String hash = com.notehive.common.CursorCodec.sha256Hex(refreshToken.getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        UserSession.findActiveByRefreshHash(hash, Instant.now()).ifPresent(s -> {
            s.revokedAt = Instant.now();
            s.revokedReason = reason;
        });
    }

    /** Server-side liveness check for a session (WebSocket revalidation). */
    public boolean isSessionActive(UUID sessionId) {
        return UserSession.<UserSession>find(
                        "sessionId = ?1 and revokedAt is null", sessionId)
                .firstResultOptional().isPresent();
    }

    // ------------------------------------------------------------------ internals
    private TokenPair issueTokens(User user, boolean rememberMe, String userAgent, String ip) {
        return issueTokens(user,
                Instant.now().plus(rememberMe ? sessionCfg.rememberMeTtl() : sessionCfg.defaultTtl()),
                UUID.randomUUID(), userAgent, ip);
    }

    private TokenPair issueTokens(User user, Instant absoluteExpiry, UUID familyId,
                                  String userAgent, String ip) {
        Instant now = Instant.now();
        String refreshToken = newToken();
        UserSession session = new UserSession();
        session.id = UUID.randomUUID();
        session.userId = user.id;
        session.orgId = user.orgId;
        session.familyId = familyId;
        session.refreshHash = com.notehive.common.CursorCodec.sha256Hex(refreshToken.getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        session.idleExpiresAt = now.plus(sessionCfg.idleWindow());
        session.absoluteExpiresAt = absoluteExpiry;
        session.userAgent = truncate(userAgent, 200);
        session.ip = truncate(ip, 64);
        session.persist();

        String access = jwt.issue(user.id.toString(), user.orgId.toString(),
                session.id.toString(), "owner",
                sessionCfg.accessTokenTtl().toSeconds());
        return new TokenPair(access, refreshToken,
                sessionCfg.accessTokenTtl().toSeconds(), session.id);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** URL-safe slug from the org name, suffix-guaranteed unique. */
    private String uniqueSlug(String orgName) {
        String base = orgName.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isBlank()) {
            base = "org";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String candidate = base;
        while (Organization.slugExists(candidate)) {
            candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return candidate;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void bindGuc(UUID orgId) {
        em.createNativeQuery("SELECT set_config('app.tenant_id', ?1, true)")
                .setParameter(1, orgId.toString())
                .getSingleResult();
    }
}
