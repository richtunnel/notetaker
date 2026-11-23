package com.notehive.common;

import java.util.List;

/** Keyset pagination envelope. */
public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {

    public static <T> Page<T> of(List<T> items, String nextCursor, boolean hasMore) {
        return new Page<>(List.copyOf(items), nextCursor, hasMore);
    }
}
