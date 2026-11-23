package com.notehive.event;

/**
 * Retry schedule: exponential backoff with full jitter (capped), so bursts of
 * failures spread out instead of synchronizing ("thundering retry").
 * attempt N (1-based) => base * 2^(N-1) * random(0.5..1.5), capped at 30s.
 */
public final class Backoff {

    private Backoff() {}

    public static long nextDelayMillis(int attempt, long baseMillis, long capMillis) {
        int n = Math.max(1, attempt);
        long exp = baseMillis;
        for (int i = 1; i < n && exp < capMillis; i++) {
            exp = Math.min(capMillis, exp * 2);
        }
        exp = Math.min(capMillis, exp);
        double jitter = 0.5 + java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        return Math.max(1, (long) (exp * Math.min(1.5, jitter)));
    }
}
