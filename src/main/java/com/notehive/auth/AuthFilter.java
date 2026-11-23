package com.notehive.auth;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.GlobalExceptionMapper;
import com.notehive.common.TenantContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.ext.Provider;
import org.jose4j.jwt.JwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Single authentication middleware for /api/**:
 *
 *  1. Authorization: Bearer <JWT>  — API clients / service-to-service.
 *     RS256 verified (static key or OIDC JWKS), issuer+audience+exp enforced.
 *  2. Session cookie (notehive_at) — browsers. Same JWT verification, PLUS
 *     CSRF double-submit enforcement on unsafe methods (X-CSRF-Token must
 *     match the csrf cookie) because a cookie is an ambient credential.
 *
 * Both paths populate {@link TenantContext}. No tenant id is ever accepted
 * from client headers — it comes only from verified token claims.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);

    public static final String ACCESS_COOKIE = "notehive_at";
    public static final String REFRESH_COOKIE = "notehive_rt";
    public static final String CSRF_COOKIE = "csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "api/auth/login", "api/auth/register", "api/auth/username-available",
            "api/auth/email-available", "api/auth/refresh", "api/auth/logout");

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Inject JwtService jwt;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = ctx.getUriInfo().getPath().replaceFirst("^/", "");
        if (!path.startsWith("api/")) {
            return;
        }
        if (PUBLIC_PATHS.contains(path)) {
            return;
        }
        String bearer = bearerToken(ctx.getHeaderString("Authorization"));
        if (bearer != null) {
            authenticate(ctx, bearer, false);
            return;
        }
        Cookie access = ctx.getCookies().get(ACCESS_COOKIE);
        if (access != null && !access.getValue().isBlank()) {
            String csrfCookie = ctx.getCookies().get(CSRF_COOKIE) == null
                    ? null : ctx.getCookies().get(CSRF_COOKIE).getValue();
            String csrfHeader = ctx.getHeaderString(CSRF_HEADER);
            if (UNSAFE_METHODS.contains(ctx.getMethod()) &&
                    (csrfCookie == null || csrfHeader == null || !csrfCookie.equals(csrfHeader))) {
                abort(ctx, ErrorCode.CSRF_INVALID, "CSRF token missing or invalid");
                return;
            }
            authenticate(ctx, access.getValue(), true);
            return;
        }
        abort(ctx, ErrorCode.SESSION_INVALID, "Missing credentials");
    }

    private void authenticate(ContainerRequestContext ctx, String token, boolean cookiePath) {
        try {
            JwtClaims claims = jwt.verify(token);
            String subject = claims.getSubject();
            String tenant = claims.getStringClaimValue("tid");
            if (subject == null || tenant == null || tenant.isBlank()) {
                // SSO tokens without an embedded tenant claim are rejected:
                // the tenant is never taken from client-supplied headers.
                abort(ctx, ErrorCode.SESSION_INVALID, "Token has no tenant claim");
                return;
            }
            String sid = claims.getStringClaimValue("sid");
            String role = claims.getStringClaimValue("role");
            TenantContext.set(new TenantContext.Principal(
                    UUID.fromString(subject), UUID.fromString(tenant), sid, role));
        } catch (ApiException.Unauthorized e) {
            abort(ctx, ErrorCode.SESSION_INVALID, e.getMessage());
        } catch (org.jose4j.jwt.MalformedClaimException e) {
            abort(ctx, ErrorCode.SESSION_INVALID, "Malformed token claims");
        } catch (IllegalArgumentException e) {
            abort(ctx, ErrorCode.SESSION_INVALID, "Malformed token claims");
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response)
            throws IOException {
        TenantContext.clear();
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static void abort(ContainerRequestContext ctx, ErrorCode code, String detail) {
        ctx.abortWith(jakarta.ws.rs.core.Response.status(
                        jakarta.ws.rs.core.Response.Status.UNAUTHORIZED)
                .entity(new GlobalExceptionMapper.ErrorBody(code.name(), "Unauthorized", 401,
                        detail, "", null))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .build());
    }
}
