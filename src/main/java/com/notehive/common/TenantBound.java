package com.notehive.common;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interceptor binding: the bound transactional method executes with the
 * PostgreSQL session GUC app.tenant_id set (LOCAL to the transaction) so
 * Row-Level Security policies apply (see db/migration/V1__schema.sql).
 * The binding lives in common; the interceptor implementation lives in infra
 * because it needs the Hibernate EntityManager.
 */
@Inherited
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantBound {
}
