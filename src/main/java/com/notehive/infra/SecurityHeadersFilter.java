package com.notehive.infra;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

/** Baseline security headers on every response. */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION + 200)
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response)
            throws IOException {
        response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        response.getHeaders().putSingle("X-Frame-Options", "DENY");
        response.getHeaders().putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
        response.getHeaders().putSingle("Cache-Control", "no-store");
        response.getHeaders().putSingle("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    }
}
