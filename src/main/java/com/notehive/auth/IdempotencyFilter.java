package com.notehive.auth;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.CursorCodec;
import com.notehive.common.TenantContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Idempotency middleware for retriable writes (Idempotency-Key header).
 *
 * Contract:
 *  - first call with a key: claim a pending row (atomic upsert), execute, store
 *    the response snapshot in the same completion transaction as the business
 *    write when possible — the snapshot is written by the response filter;
 *  - retried call with the SAME key + same request hash: replay the stored
 *    response (200/whatever was recorded) without re-executing;
 *  - same key, DIFFERENT payload: 422 IDEMPOTENCY_CONFLICT (never execute);
 *  - concurrent in-flight duplicate: 409 with Retry-After;
 *  - crashed writers leave stale pending rows that a later retry can take over.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION + 10) // after AuthFilter: TenantContext is populated
public class IdempotencyFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_BUFFER = 1024 * 1024; // 1 MiB hashing cap
    private static final String PROP = "notehive.idempotency";

    @Inject IdempotencyStore store;
    @Inject com.notehive.config.AppConfig.Cache cacheCfg;

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String key = ctx.getHeaderString("Idempotency-Key");
        if (key == null || key.isBlank() || !IDEMPOTENT_METHODS.contains(ctx.getMethod())) {
            return;
        }
        if (key.length() < 8 || key.length() > 120) {
            ctx.abortWith(ApiJson.response(422, ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be 8-120 characters"));
            return;
        }
        var principal = TenantContext.getOrNull();
        if (principal == null) {
            return; // unauthenticated paths are not idempotency-wrapped
        }
        byte[] body = readBody(ctx);
        String requestHash = CursorCodec.sha256Hex((ctx.getMethod() + "|" + ctx.getUriInfo()
                .getRequestUri() + "|").getBytes(StandardCharsets.UTF_8), body);

        var claim = store.claim(principal.tenantId(), key, principal.userId(),
                ctx.getMethod(), pathWithQuery(ctx), requestHash, 120);
        if (claim.claimed()) {
            ctx.setProperty(PROP, new State(key, requestHash));
            return; // first execution — proceed, response filter completes the record
        }
        if (claim.requestHash() != null && !claim.requestHash().equals(requestHash)) {
            ctx.abortWith(ApiJson.response(422, ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key was already used with a different payload"));
            return;
        }
        if ("completed".equals(claim.status())) {
            ctx.abortWith(jakarta.ws.rs.core.Response.status(
                            claim.responseCode() == null ? 200 : claim.responseCode())
                    .entity(claim.responseBody() == null ? "{}" : claim.responseBody())
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                    .header("Idempotency-Replayed", "true")
                    .build());
            return;
        }
        ctx.abortWith(jakarta.ws.rs.core.Response.status(409)
                .entity(new com.notehive.common.GlobalExceptionMapper.ErrorBody(
                        ErrorCode.IDEMPOTENCY_IN_PROGRESS.name(), "Conflict", 409,
                        "A request with this key is still in flight; retry shortly", "", null))
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .header("Retry-After", "2")
                .build());
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response)
            throws IOException {
        State state = (State) request.getProperty(PROP);
        if (state == null || response.getStatus() >= 500) {
            return; // server errors are retriable, keep the claim pending
        }
        var principal = TenantContext.getOrNull();
        if (principal == null) {
            return;
        }
        try {
            String body = response.getEntity() == null ? "{}"
                    : (response.getEntity() instanceof String s ? s
                            : com.notehive.common.Json.write(response.getEntity()));
            store.complete(principal.tenantId(), state.key(), state.requestHash(),
                    response.getStatus(), body);
        } catch (Exception e) {
            // response recording must never break the response itself; the
            // pending row expires and the next retry takes over
            LOG.warn("Idempotency completion failed for key {}: {}", state.key(), e.getMessage());
        }
    }

    private static byte[] readBody(ContainerRequestContext ctx) throws IOException {
        if (ctx.getEntityStream() == null) {
            return new byte[0];
        }
        byte[] bytes = ctx.getEntityStream().readAllBytes();
        if (bytes.length > MAX_BUFFER) {
            throw new ApiException.TooLarge("Request body exceeds idempotency buffer");
        }
        ctx.setEntityStream(new java.io.ByteArrayInputStream(bytes));
        return bytes;
    }

    private static String pathWithQuery(ContainerRequestContext ctx) {
        return ctx.getUriInfo().getRequestUri().getPath();
    }

    record State(String key, String requestHash) {}

    /** Small helper for abort responses with the unified error body. */
    static final class ApiJson {
        private ApiJson() {}

        static jakarta.ws.rs.core.Response response(int status, ErrorCode code, String detail) {
            return jakarta.ws.rs.core.Response.status(status)
                    .entity(new com.notehive.common.GlobalExceptionMapper.ErrorBody(
                            code.name(), "Error", status, detail, "", null))
                    .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                    .build();
        }
    }
}
