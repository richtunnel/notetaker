package com.notehive.common;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bloom-filter accelerated username/email availability (hash mapping, part 2).
 *
 * Contract:
 *  - negative => address is PROBABLY available (skip the DB read on most checks);
 *  - positive => address MAY exist => ALWAYS verify against PostgreSQL;
 *  - the database UNIQUE constraint on normalized values remains the only
 *    authority; the filter is never authoritative (false positives expected);
 *  - updated on registration; rebuilt from the DB at startup (cross-tenant
 *    read via the worker datasource).
 */
public class BloomUsernameIndex {

    private volatile BloomFilter<String> filter;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final long expectedInsertions;
    private final double fpp;

    public BloomUsernameIndex(long expectedInsertions, double fpp) {
        this.expectedInsertions = expectedInsertions;
        this.fpp = fpp;
        this.filter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8),
                expectedInsertions, fpp);
    }

    /** Fast path: false means PROBABLY available (may still be taken). */
    public boolean definitelyNotPresent(String value) {
        lock.readLock().lock();
        try {
            return !filter.mightContain(normalize(value));
        } finally {
            lock.readLock().unlock();
        }
    }

    public void record(String value) {
        lock.writeLock().lock();
        try {
            filter.put(normalize(value));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Full rebuild (startup, or periodic correction of deletions). */
    public void rebuild(Collection<String> values) {
        BloomFilter<String> fresh = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8), expectedInsertions, fpp);
        values.forEach(v -> fresh.put(normalize(v)));
        lock.writeLock().lock();
        try {
            this.filter = fresh;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
