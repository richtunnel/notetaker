package com.notehive.infra;

import com.notehive.common.BloomUsernameIndex;
import com.notehive.common.CursorCodec;
import com.notehive.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * CDI producers for value objects that need configured key material.
 * Dev fallbacks are clearly warned about; production MUST set the env vars.
 */
@ApplicationScoped
public class CdiBeans {

    private static final Logger LOG = LoggerFactory.getLogger(CdiBeans.class);

    @Produces
    @ApplicationScoped
    public CursorCodec cursorCodec(AppConfig.Crypto cfg) {
        byte[] key = decode32(cfg.cursorHmacKey(),
                "notehive-dev-cursor-hmac-key-0123456789abcdef");
        return new CursorCodec(key);
    }

    @Produces
    @ApplicationScoped
    public BloomUsernameIndex bloomIndex(AppConfig.Bloom cfg) {
        return new BloomUsernameIndex(cfg.expectedEntries(), cfg.fpp());
    }

    static byte[] decode32(String base64, String devFallback) {
        if (base64 == null || base64.isBlank()) {
            LOG.warn("Using DEV fallback key material — set the env-configured 32-byte base64 key in production");
            byte[] raw = devFallback.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] key = new byte[32];
            System.arraycopy(raw, 0, key, 0, Math.min(32, raw.length));
            return key;
        }
        byte[] decoded = Base64.getDecoder().decode(base64.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException("crypto key must decode to exactly 32 bytes");
        }
        return decoded;
    }
}
