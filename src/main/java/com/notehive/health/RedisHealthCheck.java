package com.notehive.health;

import com.notehive.infra.RedisConnectionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Redis readiness: the backbone, cache and rate limiter depend on it. */
@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck {

    @Inject RedisConnectionManager redis;

    @Override
    public HealthCheckResponse call() {
        boolean up = redis.isOpen();
        return HealthCheckResponse.named("redis")
                .status(up)
                .withData("mode", up ? "ready" : "reconnecting")
                .build();
    }
}
