package com.notehive;

import com.notehive.auth.AesGcm;
import com.notehive.auth.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void hashAndVerifyRoundTrip() {
        PasswordHasher hasher = new PasswordHasher(testCrypto(210_000));
        String hash = hasher.hash("correct horse battery staple");
        assertTrue(hash.startsWith("pbkdf2$"));
        assertTrue(hasher.verify("correct horse battery staple", hash));
        assertFalse(hasher.verify("wrong password", hash));
    }

    @Test
    void uniqueSaltsProduceUniqueHashes() {
        PasswordHasher hasher = new PasswordHasher(testCrypto(210_000));
        assertNotEquals(hasher.hash("same"), hasher.hash("same"));
    }

    @Test
    void malformedStoredHashFailsClosed() {
        PasswordHasher hasher = new PasswordHasher(testCrypto(210_000));
        assertFalse(hasher.verify("x", "garbage"));
        assertFalse(hasher.verify("x", "bcrypt$10$abc$def"));
    }

    private com.notehive.config.AppConfig.Crypto testCrypto(int iterations) {
        // minimal stub of the ConfigMapping interface
        return new com.notehive.config.AppConfig.Crypto() {
            @Override public String aesKey() { return ""; }
            @Override public String cursorHmacKey() { return ""; }
            @Override public int pbkdf2Iterations() { return iterations; }
        };
    }
}
