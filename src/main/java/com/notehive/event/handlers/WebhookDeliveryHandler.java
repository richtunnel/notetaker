package com.notehive.event.handlers;

import com.notehive.auth.AesGcm;
import com.notehive.common.EventEnvelope;
import com.notehive.common.TenantContext;
import com.notehive.config.AppConfig;
import com.notehive.common.TenantBound;
import com.notehive.event.Backoff;
import com.notehive.event.EventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Enterprise outbound webhooks with retries: for each matching active endpoint
 * the payload is signed (HMAC-SHA256, timestamped) and POSTed; transient
 * failures retry in-process with exponential backoff + jitter; after
 * maxAttempts the exception rethrows so the stream PEL takes over (bounded
 * redeliveries, then DLQ). Delivery targets are tenant-scoped reads (RLS-bound).
 */
@ApplicationScoped
public class WebhookDeliveryHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryHandler.class);
    private static final Set<String> EVENT_TYPES = Set.of(
            "note.created", "note.updated", "note.deleted", "note.restored", "comment.created");

    @Inject EntityManager em;
    @Inject AppConfig.Webhooks cfg;
    @Inject AppConfig.Crypto cryptoCfg;
    @Inject MeterRegistry metrics;

    private Counter delivered;
    private Counter failed;
    private HttpClient client;
    private AesGcm aes;

    @PostConstruct
    void init() {
        delivered = Counter.builder("notehive_webhooks_delivered_total").register(metrics);
        failed = Counter.builder("notehive_webhooks_failed_total").register(metrics);
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (!cryptoCfg.aesKey().isBlank()) {
            aes = new AesGcm(Base64.getDecoder().decode(cryptoCfg.aesKey()));
        }
    }

    @Override
    public String group() {
        return "webhook-delivery";
    }

    @Override
    public Set<String> eventTypes() {
        return EVENT_TYPES;
    }

    @Override
    public void handle(EventEnvelope event) throws Exception {
        if (!cfg.enabled()) {
            return;
        }
        List<WebhookEndpoint> endpoints = loadEndpoints(event.tenantId());
        if (endpoints.isEmpty()) {
            return;
        }
        String body = com.notehive.common.Json.write(com.notehive.common.Json.objectNode()
                .put("type", event.eventType())
                .put("eventId", event.eventId().toString())
                .put("aggregateId", event.aggregateId().toString())
                .put("occurredAt", event.occurredAt().toString())
                .set("payload", event.payload()));
        for (WebhookEndpoint endpoint : endpoints) {
            deliverWithRetries(endpoint, event.eventType(), body);
        }
    }

    private void deliverWithRetries(WebhookEndpoint endpoint, String eventType, String body)
            throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= cfg.maxAttempts(); attempt++) {
            try {
                post(endpoint, eventType, body);
                delivered.increment();
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < cfg.maxAttempts()) {
                    Thread.sleep(Math.min(30_000, Backoff.nextDelayMillis(
                            attempt, cfg.backoffBase().toMillis(), 30_000)));
                }
            }
        }
        failed.increment();
        LOG.warn("Webhook {} failed after {} attempts: {}", endpoint.url, cfg.maxAttempts(),
                last == null ? "?" : last.getMessage());
        throw last != null ? last : new IllegalStateException("webhook failed");
    }

    private void post(WebhookEndpoint endpoint, String eventType, String body) throws Exception {
        if (aes == null) {
            throw new IllegalStateException("notehive.crypto.aes-key must be configured for webhooks");
        }
        String secret = aes.decrypt(new String(endpoint.secretEnc,
                java.nio.charset.StandardCharsets.ISO_8859_1));
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.url))
                .timeout(cfg.timeout())
                .header("Content-Type", "application/json")
                .header("X-NoteHive-Event", eventType)
                .header("X-NoteHive-Timestamp", timestamp)
                .header("X-NoteHive-Signature", WebhookSigner.sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
    }

    /** Tenant-scoped read: RLS-bound via TenantContext + @TenantBound. */
    @SuppressWarnings("unchecked")
    private List<WebhookEndpoint> loadEndpoints(java.util.UUID tenantId) {
        TenantContext.set(new TenantContext.Principal(null, tenantId, null, null));
        try {
            return endpointReader.list(tenantId);
        } finally {
            TenantContext.clear();
        }
    }

    @Inject EndpointReader endpointReader;

    @ApplicationScoped
    public static class EndpointReader {
        @Inject EntityManager em;

        @Transactional
        @TenantBound
        public List<WebhookEndpoint> list(java.util.UUID tenantId) {
            return WebhookEndpoint.<WebhookEndpoint>find(
                            "orgId = ?1 and active = true", tenantId)
                    .list();
        }
    }
}
