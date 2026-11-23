package com.notehive.infra;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

/**
 * Correlation middleware: accepts X-Correlation-Id from trusted proxies or
 * mints one, binds it to MDC (structured logs carry it on every line) and
 * echoes it back. Runs before authentication.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 100)
public class CorrelationIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);
    public static final String HEADER = "X-Correlation-Id";

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String incoming = ctx.getHeaderString(HEADER);
        String correlationId = incoming != null && incoming.length() <= 64 && incoming.matches("[\\w-]+")
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        ctx.setProperty("notehive.correlationId", correlationId);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response)
            throws IOException {
        String correlationId = (String) request.getProperty("notehive.correlationId");
        if (correlationId != null) {
            response.getHeaders().putSingle(HEADER, correlationId);
        }
        MDC.remove("correlationId");
    }
}
