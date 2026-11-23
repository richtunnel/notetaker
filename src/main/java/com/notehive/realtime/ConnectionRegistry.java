package com.notehive.realtime;

import io.vertx.core.http.ServerWebSocket;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local connection registry: socket metadata + server-side channel
 * subscriptions. Only sockets subscribed to a channel receive its frames —
 * and subscriptions were validated server-side before registration.
 */
@ApplicationScoped
public class ConnectionRegistry {

    public record ConnectionMeta(UUID userId, UUID tenantId, String rawToken) {}

    public static final class Entry {
        final ServerWebSocket socket;
        final ConnectionMeta meta;
        final Set<String> channels = ConcurrentHashMap.newKeySet();

        Entry(ServerWebSocket socket, ConnectionMeta meta) {
            this.socket = socket;
            this.meta = meta;
        }

        public ServerWebSocket socket() { return socket; }

        public ConnectionMeta meta() { return meta; }
    }

    private final Map<String, Entry> connections = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();

    public String register(ServerWebSocket socket, ConnectionMeta meta) {
        String connId = UUID.randomUUID().toString();
        connections.put(connId, new Entry(socket, meta));
        return connId;
    }

    /** @return channels that had this connection (for pubsub refcount cleanup). */
    public List<String> unregister(String connId) {
        Entry entry = connections.remove(connId);
        if (entry == null) {
            return List.of();
        }
        for (String channel : entry.channels) {
            Set<String> subs = channelSubscribers.get(channel);
            if (subs != null) {
                subs.remove(connId);
            }
        }
        return List.copyOf(entry.channels);
    }

    public void subscribe(String connId, String channel) {
        Entry entry = connections.get(connId);
        if (entry == null) return;
        entry.channels.add(channel);
        channelSubscribers.computeIfAbsent(channel, c -> ConcurrentHashMap.newKeySet()).add(connId);
    }

    public void unsubscribe(String connId, String channel) {
        Entry entry = connections.get(connId);
        if (entry != null) entry.channels.remove(channel);
        Set<String> subs = channelSubscribers.get(channel);
        if (subs != null) subs.remove(connId);
    }

    /** @return true when any LOCAL socket still wants this redis channel. */
    public boolean hasSubscribers(String channel) {
        Set<String> subs = channelSubscribers.get(channel);
        return subs != null && !subs.isEmpty();
    }

    public List<Entry> subscribersOf(String channel) {
        Set<String> ids = channelSubscribers.get(channel);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Entry> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Entry e = connections.get(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    public Entry entry(String connId) {
        return connections.get(connId);
    }

    public int count() {
        return connections.size();
    }
}
