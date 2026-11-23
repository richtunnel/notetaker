package com.notehive.auth;

import com.notehive.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-HmacSHA256 password hashing (OWASP: 210k iterations, per-user salt).
 * Format: pbkdf2$<iterations>$<saltB64>$<hashB64> — iterations embedded so
 * they can be raised later without breaking existing hashes (re-hash on login
 * if iterations differ, handled by SessionService).
 */
@ApplicationScoped
public class PasswordHasher {

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public PasswordHasher(AppConfig.Crypto crypto) {
        this.iterations = Math.max(100_000, crypto.pbkdf2Iterations());
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        return "pbkdf2$%d$%s$%s".formatted(iterations,
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash));
    }

    public boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iters = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iters);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }
}
