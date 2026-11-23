package com.notehive.realtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.notehive.auth.JwtService;
import com.notehive.auth.SessionService;
import com.notehive.common.Json;
import com.notehive.config.AppConfig;
import com.notehive.infra.RedisConnectionManager;
import com.notehive.event.handlers.RealtimeFanoutHandler;
import com.notehive.infra.RedisRateLimiter;
import com.notehive.note.NoteService;
import com.notehive.team.TeamService;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jose4j.jwt.JwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket gateway (connection handling):
 *  - handshake auth: bearer JWT or session cookie, verified cryptographically;
 *  - server-side subscription authorization: channels are team:{teamId} or
 *    note:{noteId}; membership is checked before any subscribe is honored —
 *    a client-declared room is NEVER trusted;
 *  - heartbeat ping/pong + periodic revalidation of the presenting token and
 *    session (a socket is not an indefinite proof of a live session); expired
 *    sockets are closed 4401 and clients reconnect with backoff;
 *  - cross-instance fan-out via Redis Pub/Sub (channels subscribed once per
 *    instance with refcount-free idempotence).
 */
@ApplicationScoped
public class RealtimeGateway {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeGateway.class);

    @Inject Router router;
    @Inject Vertx vertx;
    @Inject JwtService jwt;
    @Inject SessionService sessions;
    @Inject TeamService teams;
    @Inject NoteService noteService;
    @Inject ConnectionRegistry registry;
    @Inject RedisConnectionManager redis;
    @Inject RedisRateLimiter limiter;
    @Inject AppConfig.Session sessionCfg;

    private final Object pubsubLock = new Object();
    private final Set<String> subscribedRedisChannels = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void mount() {
        router.route("/realtime").order(1).handler(rc -> {
            String cookieHeader = rc.request().headers().get("Cookie");
            String token = bearer(rc.request().headers().get("Authorization"));
            if (token == null && cookieHeader != null) {
                token = cookieValue(cookieHeader, com.notehive.auth.AuthFilter.ACCESS_COOKIE);
            }
            com.notehive.common.TenantContext.Principal principal;
            try {
                if (token == null) {
                    rc.response().setStatusCode(401).end();
                    return;
                }
                JwtClaims claims = jwt.verify(token);
                String tid = claims.getStringClaimValue("tid");
                if (tid == null || tid.isBlank()) {
                    rc.response().setStatusCode(401).end();
                    return;
                }
                principal = new com.notehive.common.TenantContext.Principal(
                        UUID.fromString(claims.getSubject()),
                        UUID.fromString(tid),
                        claims.getStringClaimValue("sid"),
                        claims.getStringClaimValue("role"));
            } catch (Exception e) {
                rc.response().setStatusCode(401).end();
                return;
            }
            var attempt = limiter.attempt("ws-connect", principal.userId().toString(),
                    sessionCfg.wsConnectRateLimit(), sessionCfg.wsConnectRateWindow());
            if (!attempt.allowed()) {
                rc.response().setStatusCode(429).end();
                return;
            }
            Future<ServerWebSocket> wsFuture = rc.request().toWebSocket();
            final String presentingToken = token;
            wsFuture.onSuccess(ws -> attach(ws, principal, presentingToken))
                    .onFailure(err -> LOG.warn("WS upgrade failed: {}", err.getMessage()));
        });

        redis.pubSub().addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                String logical = toLogicalChannel(channel);
                vertx.executeBlocking(promise -> {
                    for (ConnectionRegistry.Entry entry : registry.subscribersOf(logical)) {
                        try {
                            entry.socket().writeTextMessage(message);
                        } catch (Exception ignored) {
                            // socket died between registry and write
                        }
                    }
                    promise.complete();
                }, false);
            }
        });
        LOG.info("Realtime gateway mounted at /realtime");
    }

    private void attach(ServerWebSocket ws,
                        com.notehive.common.TenantContext.Principal principal,
                        String rawToken) {
        String connId = registry.register(ws, new ConnectionRegistry.ConnectionMeta(
                principal.userId(), principal.tenantId(), rawToken));

        ws.textMessageHandler(raw -> vertx.executeBlocking(
                promise -> {
                    try {
                        handleFrame(connId, raw, principal);
                        promise.complete();
                    } catch (Exception e) {
                        promise.fail(e);
                    }
                }, false)
                .onFailure(err -> sendError(ws, "FRAME_ERROR", String.valueOf(err.getMessage()))));

        ws.closeHandler(v -> {
            for (String channel : registry.unregister(connId)) {
                releaseIfIdle(channel, toRedisChannel(channel));
            }
        });

        ObjectNode hello = Json.objectNode()
                .put("type", "hello")
                .put("connId", connId)
                .put("userId", principal.userId().toString());
        ws.writeTextMessage(Json.write(hello));

        // periodic revalidation: token still valid? session still live?
        vertx.setPeriodic(60_000, timer -> {
            if (registry.entry(connId) == null) {
                vertx.cancelTimer(timer);
                return;
            }
            boolean tokenValid;
            try {
                jwt.verify(rawToken);
                tokenValid = true;
            } catch (Exception e) {
                tokenValid = false;
            }
            boolean sessionValid = principal.sessionId() == null || principal.sessionId().isBlank()
                    || sessions.isSessionActive(UUID.fromString(principal.sessionId()));
            if (!tokenValid || !sessionValid) {
                sendError(ws, "SESSION_EXPIRED", "Please reconnect after refreshing your session");
                try {
                    ws.close((short) 4401, "session expired");
                } catch (Exception ignored) {
                    // already closed
                }
                for (String channel : registry.unregister(connId)) {
                    releaseIfIdle(channel, toRedisChannel(channel));
                }
                vertx.cancelTimer(timer);
            }
        });
    }

    private void handleFrame(String connId, String raw,
                             com.notehive.common.TenantContext.Principal principal) {
        ObjectNode frame;
        try {
            frame = (ObjectNode) Json.parse(raw);
        } catch (Exception e) {
            ConnectionRegistry.Entry broken = registry.entry(connId);
            sendError(broken == null ? null : broken.socket(), "BAD_FRAME", "Malformed JSON");
            return;
        }
        String type = frame.path("type").asText("");
        ConnectionRegistry.Entry entry = registry.entry(connId);
        if (entry == null) {
            return; // connection closed concurrently
        }
        switch (type) {
            case "heartbeat" -> entry.socket().writeTextMessage(
                    Json.write(Json.objectNode().put("type", "pong")));
            case "subscribe" -> subscribe(connId, principal,
                    frame.path("channel").asText(""));
            case "unsubscribe" -> {
                String channel = frame.path("channel").asText("");
                registry.unsubscribe(connId, channel);
                releaseIfIdle(channel, toRedisChannel(channel));
                entry.socket().writeTextMessage(Json.write(Json.objectNode()
                        .put("type", "unsubscribed").put("channel", channel)));
            }
            default -> sendError(entry.socket(), "UNKNOWN_TYPE", "Unsupported frame type: " + type);
        }
    }

    /** Server-side authorization for every subscribe request. */
    private void subscribe(String connId, com.notehive.common.TenantContext.Principal principal,
                           String channel) {
        ConnectionRegistry.Entry entry = registry.entry(connId);
        if (entry == null) return;
        String verdict = channelAuthorized(principal, channel);
        if (verdict != null) {
            sendError(entry.socket(), "FORBIDDEN", verdict);
            return;
        }
        boolean hadSubscribers = registry.hasSubscribers(channel);
        registry.subscribe(connId, channel);
        acquireRedisChannel(toRedisChannel(channel));
        entry.socket().writeTextMessage(Json.write(Json.objectNode()
                .put("type", "subscribed").put("channel", channel)));
        LOG.debug("ws subscribed conn={} channel={} (had={})", connId, channel, hadSubscribers);
    }

    private String channelAuthorized(com.notehive.common.TenantContext.Principal principal,
                                     String channel) {
        if (channel == null || channel.isBlank()) {
            return "Channel required";
        }
        String[] parts = channel.split(":", 2);
        if (parts.length != 2) {
            return "Invalid channel format";
        }
        UUID id;
        try {
            id = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return "Invalid channel id";
        }
        try {
            switch (parts[0]) {
                case "team" -> teams.requireMember(principal.tenantId(), principal.userId(), id);
                case "note" -> {
                    // note access == membership of the note's team (RLS-backed lookup)
                    Optional<UUID> teamId = noteTeam(principal.tenantId(), id);
                    if (teamId.isEmpty()) {
                        return "Note not found";
                    }
                    teams.requireMember(principal.tenantId(), principal.userId(), teamId.get());
                }
                default -> {
                    return "Unknown channel kind";
                }
            }
        } catch (Exception e) {
            return "Not authorized for this channel";
        }
        return null;
    }

    private Optional<UUID> noteTeam(UUID tenantId, UUID noteId) {
        try {
            return Optional.ofNullable(noteService.teamOfPublic(tenantId, noteId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ pubsub lifecycle

    private void acquireRedisChannel(String redisChannel) {
        synchronized (pubsubLock) {
            if (subscribedRedisChannels.contains(redisChannel)) {
                return;
            }
            redis.pubSub().sync().subscribe(redisChannel);
            subscribedRedisChannels.add(redisChannel);
        }
    }

    private void releaseIfIdle(String logicalChannel, String redisChannel) {
        synchronized (pubsubLock) {
            if (!registry.hasSubscribers(logicalChannel)
                    && subscribedRedisChannels.remove(redisChannel)) {
                try {
                    redis.pubSub().sync().unsubscribe(redisChannel);
                } catch (Exception e) {
                    LOG.debug("pubsub unsubscribe failed for {}: {}", redisChannel, e.getMessage());
                }
            }
        }
    }

    private static String toRedisChannel(String channel) {
        if (channel.startsWith("team:")) {
            return RealtimeFanoutHandler.TEAM_CHANNEL_PREFIX + channel.substring(5);
        }
        if (channel.startsWith("note:")) {
            return RealtimeFanoutHandler.NOTE_CHANNEL_PREFIX + channel.substring(5);
        }
        return "nh:" + channel;
    }

    private static String toLogicalChannel(String redisChannel) {
        if (redisChannel.startsWith(RealtimeFanoutHandler.TEAM_CHANNEL_PREFIX)) {
            return "team:" + redisChannel.substring(RealtimeFanoutHandler.TEAM_CHANNEL_PREFIX.length());
        }
        if (redisChannel.startsWith(RealtimeFanoutHandler.NOTE_CHANNEL_PREFIX)) {
            return "note:" + redisChannel.substring(RealtimeFanoutHandler.NOTE_CHANNEL_PREFIX.length());
        }
        return redisChannel.startsWith("nh:") ? redisChannel.substring(3) : redisChannel;
    }

    private static void sendError(ServerWebSocket ws, String code, String detail) {
        if (ws == null) return;
        try {
            ws.writeTextMessage(Json.write(Json.objectNode()
                    .put("type", "error").put("code", code).put("detail", detail)));
        } catch (Exception ignored) {
            // socket closed
        }
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String cookieValue(String cookieHeader, String name) {
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                String value = trimmed.substring(name.length() + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
