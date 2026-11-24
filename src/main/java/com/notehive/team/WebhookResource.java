package com.notehive.team;

import com.notehive.auth.AesGcm;
import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.TenantContext;
import com.notehive.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Webhook management (org-scoped). Secrets are generated server-side,
 * AES-GCM encrypted at rest, and shown ONCE at creation.
 */
@Path("/api/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class WebhookResource {

    private static final Pattern HTTPS_URL = Pattern.compile("^https://[\\w.-]+(:\\d+)?(/[\\w./?%&=-]*)?$");

    @Inject EntityManager em;
    @Inject AppConfig.Crypto cryptoCfg;

    public record RegisterRequest(String url, List<String> eventTypes) {}

    public record EndpointView(UUID id, String url, List<String> eventTypes, String createdAt) {}

    @POST
    @Transactional
    public Map<String, Object> register(RegisterRequest req) {
        var p = TenantContext.get();
        if (req == null || req.url() == null || !HTTPS_URL.matcher(req.url()).matches()) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED,
                    "url must be a valid https:// URL");
        }
        if (cryptoCfg.aesKey().orElse("").isBlank()) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED,
                    "notehive.crypto.aes-key must be configured to store webhook secrets");
        }
        AesGcm aes = new AesGcm(Base64.getDecoder().decode(cryptoCfg.aesKey().orElse("")));
        String secret = "whsec_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        UUID endpointId = UUID.randomUUID();
        List<String> types = req.eventTypes() == null ? List.of() : req.eventTypes();
        em.createNativeQuery("""
                INSERT INTO webhook_endpoints (id, org_id, url, secret_enc, event_types, active, created_at)
                VALUES (?1, ?2, ?3, ?4, ?5, true, now())
                """)
                .setParameter(1, endpointId)
                .setParameter(2, p.tenantId())
                .setParameter(3, req.url())
                .setParameter(4, aes.encrypt(secret).getBytes(StandardCharsets.ISO_8859_1))
                .setParameter(5, types.toArray(new String[0]))
                .executeUpdate();
        // the secret is displayed exactly once — store it at the receiver now
        return Map.of("id", endpointId, "secret", secret);
    }

    @GET
    public List<EndpointView> list() {
        var p = TenantContext.get();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, url, event_types, created_at FROM webhook_endpoints
                WHERE org_id = current_setting('app.tenant_id', true)::uuid
                ORDER BY created_at DESC LIMIT 100
                """)
                .getResultList();
        List<EndpointView> out = new ArrayList<>();
        for (Object[] r : rows) {
            var types = new ArrayList<String>();
            if (r[2] instanceof java.sql.Array arr) {
                try {
                    for (String t : (String[]) arr.getArray()) {
                        types.add(t);
                    }
                } catch (Exception ignored) { }
            }
            out.add(new EndpointView((UUID) r[0], (String) r[1], types, r[3].toString()));
        }
        return out;
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathParam("id") UUID endpointId) {
        var p = TenantContext.get();
        int deleted = em.createNativeQuery(
                        "DELETE FROM webhook_endpoints WHERE id = ?1 AND org_id = " +
                        "current_setting('app.tenant_id', true)::uuid")
                .setParameter(1, endpointId)
                .executeUpdate();
        if (deleted == 0) {
            throw new ApiException.NotFound(ErrorCode.FORBIDDEN, "Endpoint not found");
        }
        return Map.of("ok", true);
    }
}
