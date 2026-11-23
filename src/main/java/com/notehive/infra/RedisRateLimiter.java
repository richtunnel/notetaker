package com.notehive.infra;

import io.lettuce.core.ScriptOutputType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Redis sliding-window rate limiter (atomic ZSET + Lua, no INCR race).
 * One script does: trim expired entries, count, admit or refuse, and compute
 * the retry-after delay from the oldest surviving entry — a single round trip.
 */
@ApplicationScoped
public class RedisRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RedisRateLimiter.class);

    // KEYS[1]=bucket, ARGV: nowMillis, windowMillis, limit, nonce
    // returns {allowed(0/1), retryAfterMillis}
    private static final String LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count < limit then
              redis.call('ZADD', key, now, now .. '-' .. ARGV[4])
              redis.call('PEXPIRE', key, window)
              return {1, 0}
            end
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local retry = 1
            if oldest[2] ~= nil then
              retry = math.max(1, math.ceil(tonumber(oldest[2]) + window - now))
            end
            return {0, retry}""";

    @Inject RedisConnectionManager redis;

    /** @return true when the subject is within the limit. */
    public boolean tryAcquire(String bucket, String subjectKey, int limit, java.time.Duration window) {
        return attempt(bucket, subjectKey, limit, window).allowed();
    }

    public Attempt attempt(String bucket, String subjectKey, int limit, java.time.Duration window) {
        long now = Instant.now().toEpochMilli();
        long windowMillis = Math.max(1, window.toMillis());
        String key = "rl:" + bucket + ":" + subjectKey;
        try {
            Long[] result = redis.commands().sync().eval(
                    LUA, ScriptOutputType.MULTI,
                    new String[]{key},
                    String.valueOf(now), String.valueOf(windowMillis),
                    String.valueOf(limit), String.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
            boolean allowed = result != null && result.length > 0 && result[0] == 1L;
            long retryAfter = result != null && result.length > 1 && result[1] != null ? result[1] : 1000;
            return new Attempt(allowed, retryAfter);
        } catch (Exception e) {
            // availability over strictness: fail-open, log, do not take the API down
            LOG.warn("Rate limiter unavailable (fail-open): {}", e.getMessage());
            return new Attempt(true, 0);
        }
    }

    public record Attempt(boolean allowed, long retryAfterMillis) {}
}
