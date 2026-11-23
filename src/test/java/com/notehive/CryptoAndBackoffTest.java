package com.notehive;

import com.notehive.auth.AesGcm;
import com.notehive.event.Backoff;
import com.notehive.event.handlers.WebhookSigner;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CryptoAndBackoffTest {

    @Test
    void aesGcmRoundTrip() {
        AesGcm aes = new AesGcm(Base64.getDecoder().decode(
                Base64.getEncoder().encodeToString(new byte[32])));
        String secret = "whsec_abcdef1234567890";
        String stored = aes.encrypt(secret);
        assertTrue(stored.startsWith("v1$"));
        assertEquals(secret, aes.decrypt(stored));
    }

    @Test
    void aesGcmRejectsTampering() {
        byte[] key = new byte[32];
        key[0] = 7;
        AesGcm aes = new AesGcm(key);
        String stored = aes.encrypt("data");
        String tampered = stored.substring(0, stored.length() - 2) + "AA";
        assertThrows(Exception.class, () -> aes.decrypt(tampered));
    }

    @Test
    void aesGcmRejectsWrongKeySize() {
        assertThrows(IllegalArgumentException.class, () -> new AesGcm(new byte[16]));
    }

    @Test
    void webhookSignatureIsDeterministicAndBoundToBody() {
        String sig1 = WebhookSigner.sign("secret", "1700000000", "{\"a\":1}");
        String sig2 = WebhookSigner.sign("secret", "1700000000", "{\"a\":1}");
        String sig3 = WebhookSigner.sign("secret", "1700000000", "{\"a\":2}");
        assertEquals(sig1, sig2);
        assertNotEquals(sig1, sig3);
        assertFalse(sig1.isBlank());
    }

    @Test
    void backoffGrowsExponentiallyAndIsJittered() {
        long base = 500, cap = 30_000;
        // deterministic pre-jitter schedule: 500,1000,2000,4000,8000,16000,30000(capped),30000
        long[] expectedBase = {500, 1000, 2000, 4000, 8000, 16000, 30000, 30000};
        Set<Long> distinct = new HashSet<>();
        long previousMax = 0;
        for (int attempt = 1; attempt <= 8; attempt++) {
            long maxForAttempt = 0;
            long minForAttempt = Long.MAX_VALUE;
            for (int i = 0; i < 500; i++) {
                long d = Backoff.nextDelayMillis(attempt, base, cap);
                assertTrue(d >= 1);
                assertTrue(d <= 45_000, "capped schedule * jitter(<=1.5) must stay <= 45s");
                maxForAttempt = Math.max(maxForAttempt, d);
                minForAttempt = Math.min(minForAttempt, d);
                distinct.add(d);
            }
            // with 500 samples the max approaches the upper jitter bound
            assertTrue(maxForAttempt >= expectedBase[attempt - 1],
                    "attempt " + attempt + " max " + maxForAttempt + " should reach its base");
            assertTrue(minForAttempt >= expectedBase[attempt - 1] / 2,
                    "jitter floor is half the base");
            if (attempt >= 2 && expectedBase[attempt - 1] > expectedBase[attempt - 2]) {
                assertTrue(maxForAttempt > previousMax,
                        "delay should grow: attempt " + attempt);
            }
            previousMax = maxForAttempt;
        }
        assertTrue(distinct.size() > 10, "jitter should produce varied delays");
    }

    @Test
    void backoffIsCapped() {
        for (int i = 0; i < 100; i++) {
            long d = Backoff.nextDelayMillis(20, 500, 30_000);
            assertTrue(d <= 30_000 * 1.5, "cap must hold even with jitter");
        }
    }
}
