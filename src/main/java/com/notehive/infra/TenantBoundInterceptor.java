package com.notehive.infra;

import com.notehive.common.TenantBound;
import com.notehive.common.TenantContext;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;

import java.util.UUID;

/**
 * Binds the PostgreSQL session GUC app.tenant_id (LOCAL to the transaction)
 * at method entry so Row-Level Security policies apply on every statement of
 * the transaction (V1__schema.sql). SET LOCAL guarantees the setting dies with
 * the transaction — pooled connections never leak tenant context. Requests
 * without a resolved principal proceed WITHOUT the GUC: under RLS that means
 * zero rows / failed WITH CHECK — fail-closed.
 */
@Interceptor
@TenantBound
@jakarta.annotation.Priority(jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE + 10)
public class TenantBoundInterceptor {

    @Inject
    EntityManager em;

    @AroundInvoke
    public Object bindTenant(jakarta.interceptor.InvocationContext ctx) throws Exception {
        var principal = TenantContext.getOrNull();
        UUID tenantId = principal == null ? null : principal.tenantId();
        if (tenantId != null) {
            em.createNativeQuery("SELECT set_config('app.tenant_id', ?1, true)")
                    .setParameter(1, tenantId.toString())
                    .getSingleResult();
        }
        return ctx.proceed();
    }
}
