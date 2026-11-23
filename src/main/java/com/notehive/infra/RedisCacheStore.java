package com.notehive.infra;

import com.google.common.util.concurrent.Striped;
import com.notehive.common.Compressor;
import com.notehive.common.Json;
import com.notehive.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

/**
 * Redis cache-aside store.
 *
 * Expiration: every entry carries an explicit TTL from the central registry
 * (AppConfig.Cache) — nothing is cached forever, "only if necessary".
 * Invalidation: version-fenced keys. Invalidate bumps a per-entity version
 * counter (INCR); readers always seek {family}:{tenant}:{id}:v{version}, so a
 * stale writer that computed an old key AFTER an invalidation can never
 * repopulate a live key (no delete/recreate race).
 * Stampede: single-flight loaders — a striped lock ensures one loader per key
 * per instance, others wait then re-read.
 * Compression: values above gzip threshold are stored gzip-deflated with a
 * "gz:" prefix (halves typical note-view payloads on the wire).
 */
@ApplicationScoped
public class RedisCacheStore {

    private static final String GZ_PREFIX = "gz:";

    @Inject RedisConnectionManager redis;
    @Inject AppConfig.Cache cacheConfig;

    private final Striped<Lock> locks = Striped.lock(256);

    private String versionKey(String family, String tenantId, String id) {
        return "cache:ver:" + family + ":" + tenantId + ":" + id;
    }

    private String dataKey(String family, String tenantId, String id, long version) {
        return "cache:" + family + ":" + tenantId + ":" + id + ":v" + version;
    }

    public <T> T getOrLoad(String family, String tenantId, String id, Class<T> type,
                           Duration ttl, Supplier<T> loader) {
        long version = currentVersion(family, tenantId, id);
        String key = dataKey(family, tenantId, id, version);
        String cached = redis.commands().sync().get(key);
        if (cached != null) {
            return deserialize(cached, type);
        }
        Lock lock = locks.get(key);
        lock.lock();
        try {
            // double-check after acquiring (another thread may have loaded)
            version = currentVersion(family, tenantId, id);
            key = dataKey(family, tenantId, id, version);
            cached = redis.commands().sync().get(key);
            if (cached != null) {
                return deserialize(cached, type);
            }
            T value = loader.get();
            putValue(key, Json.write(value), ttl);
            return value;
        } finally {
            lock.unlock();
        }
    }

    public void put(String family, String tenantId, String id, Object value, Duration ttl) {
        long version = currentVersion(family, tenantId, id);
        putValue(dataKey(family, tenantId, id, version), Json.write(value), ttl);
    }

    public void invalidate(String family, String tenantId, String id) {
        // bump version: old data keys become unreachable; lazy-expire via TTL
        String vKey = versionKey(family, tenantId, id);
        redis.commands().sync().incr(vKey);
        redis.commands().sync().expire(vKey, cacheConfig.versionCounter().toSeconds());
    }

    private void putValue(String key, String json, Duration ttl) {
        String value = json;
        if (json.length() > cacheConfig.gzipThreshold()) {
            value = GZ_PREFIX + java.util.Base64.getEncoder().encodeToString(
                    Compressor.gzip(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        redis.commands().sync().setex(key, Math.max(1, ttl.toSeconds()), value);
    }

    private <T> T deserialize(String cached, Class<T> type) {
        String json = cached;
        if (json.startsWith(GZ_PREFIX)) {
            json = new String(Compressor.gunzip(
                    java.util.Base64.getDecoder().decode(json.substring(GZ_PREFIX.length()))),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return Json.read(json, type);
    }

    private long currentVersion(String family, String tenantId, String id) {
        String v = redis.commands().sync().get(versionKey(family, tenantId, id));
        return v == null ? 0 : Long.parseLong(v);
    }
}
