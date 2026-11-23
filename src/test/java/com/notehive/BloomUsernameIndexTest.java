package com.notehive;

import com.notehive.common.BloomUsernameIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BloomUsernameIndexTest {

    private final BloomUsernameIndex bloom = new BloomUsernameIndex(10_000, 0.01);

    @Test
    void negativeMeansProbablyAvailable() {
        bloom.record("alice");
        bloom.record("bob@example.com");
        assertFalse(bloom.definitelyNotPresent("alice"));
        assertFalse(bloom.definitelyNotPresent("ALICE")); // normalized
        assertTrue(bloom.definitelyNotPresent("carol"));
    }

    @Test
    void positiveRequiresDbVerification() {
        bloom.record("dave");
        // positive is only "maybe" — the DB remains authoritative; the filter
        // contract here is: negative => skip DB, positive => verify in DB.
        assertFalse(bloom.definitelyNotPresent("dave"));
    }

    @Test
    void falsePositiveRateStaysBelowBound() {
        for (int i = 0; i < 5_000; i++) {
            bloom.record("user" + i);
        }
        int falsePositives = 0;
        for (int i = 0; i < 5_000; i++) {
            if (!bloom.definitelyNotPresent("unknown" + i)) {
                falsePositives++;
            }
        }
        assertTrue(falsePositives < 5_000 * 0.02,
                "fpp should stay near 1% (got " + falsePositives + ")");
    }

    @Test
    void rebuildReplacesState() {
        bloom.record("old-user");
        List<String> fresh = new ArrayList<>(List.of("new-user"));
        bloom.rebuild(fresh);
        assertTrue(bloom.definitelyNotPresent("old-user"));
        assertFalse(bloom.definitelyNotPresent("new-user"));
    }
}
