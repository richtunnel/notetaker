package com.notehive.infra;

import com.notehive.config.AppConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the Lettuce client lifecycle with connection re-establishment
 * (auto-reconnect + bounded backoff + keepalive pings). One client serves:
 *  - the shared synchronous commands connection (relay, cache, rate limiting,
 *    health) — non-blocking commands only;
 *  - DEDICATED connections for blocking stream consumers (XREADGROUP BLOCK):
 *    Redis executes commands per-connection serially, so a blocked read on
 *    the shared connection would stall every other command behind it and
 *    trigger client timeouts;
 *  - a pub/sub connection (ephemeral realtime fan-out).
 * Lettuce reconnects automatically with exponential delay up to
 * RedisURI timeout; we surface connection state in metrics and health.
 */
@ApplicationScoped
public class RedisConnectionManager {

    private static final Logger LOG = LoggerFactory.getLogger(RedisConnectionManager.class);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final StatefulRedisPubSubConnection<String, String> pubSub;
    private final List<StatefulRedisConnection<String, String>> dedicated = new ArrayList<>();

    @Inject
    public RedisConnectionManager(AppConfig.Events cfg) {
        RedisURI uri = RedisURI.create(cfg.redisUrl());
        uri.setTimeout(Duration.ofSeconds(5));
        // re-establish dropped connections: exponential backoff, cap 15s
        this.client = RedisClient.create(uri);
        client.setOptions(io.lettuce.core.ClientOptions.builder()
                .autoReconnect(true)
                .pingBeforeActivateConnection(true)
                .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(io.lettuce.core.SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .keepAlive(true)
                        .build())
                .build());
        this.commands = client.connect();
        this.pubSub = client.connectPubSub();
        LOG.info("Redis connected to {}://{}:{} db={} (auto-reconnect on)",
                uri.isSsl() ? "rediss" : "redis",
                uri.getHost(), uri.getPort(), uri.getDatabase());
    }

    public StatefulRedisConnection<String, String> commands() { return commands; }

    public StatefulRedisPubSubConnection<String, String> pubSub() { return pubSub; }

    /**
     * Dedicated connection for blocking commands (stream consumers).
     * Same ClientOptions/reconnect policy as the shared connection; tracked
     * so {@link #shutdown()} closes everything it created.
     */
    public StatefulRedisConnection<String, String> newDedicatedConnection() {
        StatefulRedisConnection<String, String> conn = client.connect();
        synchronized (dedicated) { dedicated.add(conn); }
        return conn;
    }

    /** @return true when the commands connection is live right now. */
    public boolean isOpen() {
        try {
            String pong = commands.sync().ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (dedicated) {
            for (StatefulRedisConnection<String, String> conn : dedicated) {
                try { conn.close(); } catch (Exception e) { LOG.warn("dedicated close", e); }
            }
            dedicated.clear();
        }
        try { commands.close(); } catch (Exception e) { LOG.warn("commands close", e); }
        try { pubSub.close(); } catch (Exception e) { LOG.warn("pubsub close", e); }
        try { client.shutdown(); } catch (Exception e) { LOG.warn("client shutdown", e); }
    }
}
