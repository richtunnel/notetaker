package com.notehive.auth;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestHeader;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Auth surface. Cookies: HttpOnly access token (15m) + HttpOnly refresh
 * (path-scoped to /api/auth) + a readable csrf cookie for double-submit.
 * Refresh is sent as a raw body field — cookie path-scoping keeps it away
 * from application endpoints; SameSite=Lax blocks cross-site posting.
 */
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AuthResource {

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{2,39}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Inject SessionService sessions;
    @Inject AppConfig.Session sessionCfg;
    @Inject com.notehive.common.BloomUsernameIndex bloom;
    @Inject com.notehive.infra.RedisRateLimiter limiter;

    public record AuthView(String accessToken, String refreshToken, UUID sessionId,
                           UUID userId, UUID tenantId, long expiresIn) {}

    public record RegisterRequest(String orgName, String username, String email,
                                  String password, boolean rememberMe) {}

    public record LoginRequest(String emailOrUsername, String password, boolean rememberMe) {}

    public record RefreshRequest(String refreshToken) {}

    public record LogoutRequest(String refreshToken) {}

    @POST
    @Path("/register")
    public Response register(RegisterRequest req, @RestHeader("User-Agent") String userAgent,
                             jakarta.ws.rs.core.HttpHeaders headers) {
        validate(req == null, "body required");
        validate(req.orgName == null || req.orgName.isBlank()
                || req.orgName.length() > 120, "orgName required (max 120)");
        validate(req.username == null || !USERNAME.matcher(req.username).matches(),
                "username must match [a-zA-Z0-9][a-zA-Z0-9._-]{2,39}");
        validate(req.email == null || !EMAIL.matcher(req.email).matches(), "valid email required");
        validate(req.password == null || req.password.length() < 10
                || req.password.length() > 128, "password must be 10-128 characters");

        var clientIp = headers.getHeaderString("X-Forwarded-For");
        var attempt = limiter.attempt("auth-register", clientIp == null ? "unknown" : clientIp,
                sessionCfg.registerRateLimit(), sessionCfg.registerRateWindow());
        if (!attempt.allowed()) {
            throw new ApiException.RateLimited("Too many registrations", retrySeconds(attempt));
        }
        // bloom pre-check (hash mapping): negative => skip DB, positive => verify
        if (bloom.definitelyNotPresent(req.email) && bloom.definitelyNotPresent(req.username)) {
            // probably free — authoritative UNIQUE constraints still decide
        }
        var pair = sessions.register(req.orgName, req.username, req.email, req.password,
                req.rememberMe, userAgent, clientIp);
        bloom.record(req.username);
        bloom.record(req.email);
        return withCookies(Response.ok(view(pair, pair.sessionId())), pair);
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest req, @RestHeader("User-Agent") String userAgent,
                          jakarta.ws.rs.core.HttpHeaders headers) {
        validate(req == null, "body required");
        var clientIp = headers.getHeaderString("X-Forwarded-For");
        var attempt = limiter.attempt("auth-login", clientIp == null ? "unknown" : clientIp,
                sessionCfg.loginRateLimit(), sessionCfg.loginRateWindow());
        if (!attempt.allowed()) {
            throw new ApiException.RateLimited("Too many login attempts", retrySeconds(attempt));
        }
        var pair = sessions.login(req.emailOrUsername, req.password, req.rememberMe,
                userAgent, clientIp);
        return withCookies(Response.ok(view(pair, pair.sessionId())), pair);
    }

    @POST
    @Path("/refresh")
    public Response refresh(RefreshRequest req, @RestHeader("User-Agent") String userAgent,
                            jakarta.ws.rs.core.HttpHeaders headers) {
        validate(req == null || req.refreshToken == null || req.refreshToken.isBlank(),
                "refreshToken required");
        var pair = sessions.refresh(req.refreshToken, userAgent,
                headers.getHeaderString("X-Forwarded-For"));
        return withCookies(Response.ok(view(pair, pair.sessionId())), pair);
    }

    @POST
    @Path("/logout")
    public Response logout(LogoutRequest req) {
        if (req != null && req.refreshToken != null) {
            sessions.logout(req.refreshToken, "logout");
        }
        return Response.ok(java.util.Map.of("ok", true))
                .cookie(expireCookie(AuthFilter.ACCESS_COOKIE), expireCookie(AuthFilter.REFRESH_COOKIE))
                .build();
    }

    @GET
    @Path("/me")
    public Response me() {
        var principal = com.notehive.common.TenantContext.get();
        return Response.ok(java.util.Map.of(
                "userId", principal.userId(),
                "tenantId", principal.tenantId(),
                "role", principal.role() == null ? "" : principal.role())).build();
    }

    @GET
    @Path("/username-available")
    public Response usernameAvailable(@jakarta.ws.rs.QueryParam("value") String value) {
        validate(value == null || value.length() < 3 || value.length() > 40, "value 3-40 chars");
        boolean probablyFree = bloom.definitelyNotPresent(value);
        boolean taken = User.findByUsernameLower(value).isPresent();
        return Response.ok(java.util.Map.of("available", !taken)).build();
    }

    @GET
    @Path("/email-available")
    public Response emailAvailable(@jakarta.ws.rs.QueryParam("value") String value) {
        validate(value == null || !value.contains("@") || value.length() > 254, "valid email required");
        boolean taken = User.findByEmailLower(value).isPresent();
        return Response.ok(java.util.Map.of("available", !taken)).build();
    }

    // ------------------------------------------------------------------ helpers
    private static AuthView view(SessionService.TokenPair pair, UUID sessionId) {
        User user = User.findById(java.util.UUID.fromString(
                decodeSub(pair.accessToken())));
        return new AuthView(pair.accessToken(), pair.refreshToken(), sessionId,
                user.id, user.orgId, pair.accessTtlSeconds());
    }

    private static String decodeSub(String accessToken) {
        // sub claim sits in the second JWT segment; AuthFilter re-verifies anyway
        String[] parts = accessToken.split("\\.");
        try {
            var payload = com.notehive.common.Json.parse(new String(
                    java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8));
            return payload.path("sub").asText();
        } catch (Exception e) {
            throw new ApiException.Internal("token decode failed", e);
        }
    }

    private static void validate(boolean bad, String message) {
        if (bad) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    private static long retrySeconds(com.notehive.infra.RedisRateLimiter.Attempt a) {
        return Math.max(1, a.retryAfterMillis() / 1000);
    }

    private Response withCookies(Response.ResponseBuilder rb, SessionService.TokenPair pair) {
        boolean secure = sessionCfg.secureCookies();
        int accessMaxAge = (int) pair.accessTtlSeconds();
        int refreshMaxAge = (int) Math.min(Duration.ofDays(3).toSeconds(),
                sessionCfg.rememberMeTtl().toSeconds());
        return rb
                .cookie(new NewCookie.Builder(AuthFilter.ACCESS_COOKIE)
                        .value(pair.accessToken()).path("/").maxAge(accessMaxAge)
                        .httpOnly(true).secure(secure).sameSite(NewCookie.SameSite.LAX).build())
                .cookie(new NewCookie.Builder(AuthFilter.REFRESH_COOKIE)
                        .value(pair.refreshToken()).path("/api/auth").maxAge(refreshMaxAge)
                        .httpOnly(true).secure(secure).sameSite(NewCookie.SameSite.STRICT).build())
                .cookie(new NewCookie.Builder(AuthFilter.CSRF_COOKIE)
                        .value(UUID.randomUUID().toString()).path("/").maxAge(-1)
                        .secure(secure).sameSite(NewCookie.SameSite.LAX).build())
                .build();
    }

    private static NewCookie expireCookie(String name) {
        return new NewCookie.Builder(name).value("").path("/").maxAge(0).build();
    }
}
