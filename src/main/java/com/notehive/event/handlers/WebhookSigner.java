package com.notehive.event.handlers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 webhook signing. Receivers verify with
 *   HMAC(secret, timestamp + "." + body)
 * and reject deliveries older than 5 minutes (replay protection).
 */
public final class WebhookSigner {

    private WebhookSigner() {}

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("webhook signing failed", e);
        }
    }
}
