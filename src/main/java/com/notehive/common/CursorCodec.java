package com.notehive.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * HMAC-SHA256 signed keyset pagination cursor (hash mapping, part 1).
 *
 * The cursor payload {t: updatedAt epoch micros, i: last id} is signed so a
 * client cannot tamper with the seek position (e.g. to scan other tenants' rows
 * by walking every offset). Tampering fails verification => 400.
 */
public final class CursorCodec {

    public record Cursor(long t, UUID i) {}

    private final byte[] hmacKey;

    public CursorCodec(byte[] hmacKey) {
        if (hmacKey == null || hmacKey.length < 32) {
            throw new IllegalArgumentException("cursor HMAC key must be >= 32 bytes");
        }
        this.hmacKey = hmacKey.clone();
    }

    public String encode(Cursor c) {
        String payload = c.t() + ":" + c.i();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (payload + ":" + hmac(payload)).getBytes(StandardCharsets.US_ASCII));
    }

    /** @return decoded cursor or null when absent/blank. Throws Validation on tampering. */
    public Cursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.US_ASCII);
            int lastColon = decoded.lastIndexOf(':');
            if (lastColon < 0) {
                throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "Malformed cursor");
            }
            String payload = decoded.substring(0, lastColon);
            String mac = decoded.substring(lastColon + 1);
            if (!MessageDigest.isEqual(hmac(payload).getBytes(StandardCharsets.US_ASCII),
                    mac.getBytes(StandardCharsets.US_ASCII))) {
                throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "Cursor signature invalid");
            }
            String[] parts = payload.split(":");
            if (parts.length != 2) {
                throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "Malformed cursor");
            }
            return new Cursor(Long.parseLong(parts[0]), UUID.fromString(parts[1]));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "Malformed cursor");
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (InvalidKeyException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** SHA-256 hex digest — also used for version checksums and idempotency hashing. */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 over two concatenated chunks (method|uri|header and body). */
    public static String sha256Hex(byte[] first, byte[] second) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(first);
            return HexFormat.of().formatHex(md.digest(second));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
