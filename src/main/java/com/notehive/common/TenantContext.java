package com.notehive.common;

import java.util.Set;
import java.util.UUID;

/**
 * Request-scoped security context. Populated ONLY by AuthFilter after the
 * bearer JWT or session cookie has been cryptographically verified, or by the
 * WebSocket gateway after handshake authentication. ThreadLocal-based so it
 * also works on Vert.x worker threads and WebSocket handlers.
 */
public final class TenantContext {

    public record Principal(UUID userId, UUID tenantId, String sessionId, String role) {}

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Principal p) { CURRENT.set(p); }

    public static Principal get() {
        Principal p = CURRENT.get();
        if (p == null) {
            throw new ApiException.Unauthorized(ErrorCode.SESSION_INVALID,
                    "No authenticated principal on this thread");
        }
        return p;
    }

    public static Principal getOrNull() { return CURRENT.get(); }

    public static UUID requireTenant() { return get().tenantId(); }

    public static UUID requireUser() { return get().userId(); }

    public static void clear() { CURRENT.remove(); }
}
