package com.notehive.auth;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.jwx.JsonWebStructure;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;

/**
 * RS256 JWT issuing and verification (jose4j).
 *
 * Key modes:
 *  - "static": app-issued tokens. PEM keypair from config; in dev, a keypair is
 *    auto-generated and persisted under the dev key dir so restarts keep tokens
 *    valid. This is the cookie-session + API-token path.
 *  - "jwks": tokens are verified against an OIDC provider's JWKS endpoint
 *    (Keycloak/SSO) — issuer/audience still enforced here.
 *
 * Claims: sub (user id), tid (tenant/org id), sid (session id), role, iss, aud, exp, iat.
 * Access tokens are short-lived (15m default); sessions outlive them.
 */
@ApplicationScoped
public class JwtService {

    private static final Logger LOG = LoggerFactory.getLogger(JwtService.class);

    private final com.notehive.config.AppConfig.Jwt cfg;
    private RsaJsonWebKey staticKey;             // sign + verify (static mode)
    private JwtConsumer verifier;                // verify-only consumer

    public JwtService(com.notehive.config.AppConfig.Jwt cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        if (verifier != null) {
            return; // idempotent: safe under CDI and in tests
        }
        if ("jwks".equals(cfg.keyMode())) {
            if (cfg.jwksUrl().isBlank()) {
                throw new IllegalStateException("notehive.jwt.key-mode=jwks requires jwks-url");
            }
            var resolver = new HttpsJwksVerificationKeyResolver(
                    new org.jose4j.jwk.HttpsJwks(cfg.jwksUrl()));
            this.verifier = baseConsumerBuilder().setVerificationKeyResolver(resolver).build();
            LOG.info("JWT verification via JWKS {}", cfg.jwksUrl());
            return;
        }
        // static mode: load PEMs or generate+persist a dev keypair
        PublicKey publicKey;
        PrivateKey privateKey;
        if (!cfg.publicKeyPem().isBlank() && !cfg.privateKeyPem().isBlank()) {
            publicKey = (PublicKey) readPem(cfg.publicKeyPem());
            privateKey = (PrivateKey) readPem(cfg.privateKeyPem());
            LOG.info("JWT keys loaded from configuration");
        } else {
            Path dir = Path.of(cfg.devKeyDir());
            try {
                Files.createDirectories(dir);
                Path pub = dir.resolve("public.pem");
                Path priv = dir.resolve("private.pem");
                if (Files.exists(pub) && Files.exists(priv)) {
                    publicKey = (PublicKey) readPem(Files.readString(pub));
                    privateKey = (PrivateKey) readPem(Files.readString(priv));
                    LOG.info("JWT dev keys loaded from {}", dir);
                } else {
                    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                    gen.initialize(2048);
                    KeyPair pair = gen.generateKeyPair();
                    publicKey = pair.getPublic();
                    privateKey = pair.getPrivate();
                    Files.writeString(pub, writePem(publicKey));
                    Files.writeString(priv, writePem(privateKey));
                    LOG.warn("JWT dev keys GENERATED at {} — set notehive.jwt.public-key/private-key in production", dir);
                }
            } catch (Exception e) {
                throw new IllegalStateException("JWT key initialization failed", e);
            }
        }
        this.staticKey = new RsaJsonWebKey((RSAPublicKey) publicKey);
        staticKey.setPrivateKey((RSAPrivateKey) privateKey);
        staticKey.setKeyId("notehive-1");
        staticKey.setUse("sig");
        staticKey.setAlgorithm("RS256");
        this.verifier = baseConsumerBuilder().setVerificationKey(staticKey.getPublicKey()).build();
    }

    public String issue(String userId, String tenantId, String sessionId, String role, long ttlSeconds) {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(cfg.issuer());
        claims.setAudience(cfg.audience());
        claims.setExpirationTimeMinutesInTheFuture(ttlSeconds / 60.0f);
        claims.setGeneratedJwtId();
        claims.setIssuedAtToNow();
        claims.setSubject(userId);
        claims.setClaim("tid", tenantId);
        if (sessionId != null) claims.setClaim("sid", sessionId);
        if (role != null) claims.setClaim("role", role);

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(staticKey.getPrivateKey());
        jws.setKeyIdHeaderValue(staticKey.getKeyId());
        jws.setAlgorithmHeaderValue("RS256");
        jws.setHeader("typ", "JWT");
        try {
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            throw new ApiException.Internal("JWT signing failed", e);
        }
    }

    /** @return verified claims; throws Unauthorized on any verification failure. */
    public JwtClaims verify(String token) {
        try {
            return verifier.processToClaims(token);
        } catch (Exception e) {
            throw new ApiException.Unauthorized(ErrorCode.SESSION_INVALID,
                    "Invalid or expired token");
        }
    }

    /** Quick structural parse of an UNVERIFIED token (for routing decisions only). */
    public JwtContext unverifiedContext(String token) {
        try {
            return new JwtConsumerBuilder().setSkipAllValidators()
                    .setDisableRequireSignature()
                    .build().process(token);
        } catch (Exception e) {
            throw new ApiException.Unauthorized(ErrorCode.SESSION_INVALID, "Malformed token");
        }
    }

    private JwtConsumerBuilder baseConsumerBuilder() {
        return new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setRequireSubject()
                .setAllowedClockSkewInSeconds((int) java.time.Duration.ofSeconds(30).toSeconds())
                .setExpectedIssuer(cfg.issuer())
                .setExpectedAudience(cfg.audience());
    }

    private static java.security.Key readPem(String pem) {
        try {
            String body = pem.replaceAll("-----BEGIN (RSA )?(PUBLIC|PRIVATE) KEY-----", "")
                    .replaceAll("-----END (RSA )?(PUBLIC|PRIVATE) KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            var factory = java.security.KeyFactory.getInstance("RSA");
            try {
                var spec = new java.security.spec.X509EncodedKeySpec(der);
                return factory.generatePublic(spec);
            } catch (Exception pubError) {
                var spec = new java.security.spec.PKCS8EncodedKeySpec(der);
                return factory.generatePrivate(spec);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid PEM key material", e);
        }
    }

    private static String writePem(java.security.Key key) {
        String type = key instanceof PublicKey pub
                ? (pub.getAlgorithm().equals("RSA") ? "RSA PUBLIC KEY" : "PUBLIC KEY")
                : (key.getAlgorithm().equals("RSA") ? "RSA PRIVATE KEY" : "PRIVATE KEY");
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(key.getEncoded());
        return "-----BEGIN %s-----\n%s\n-----END %s-----\n".formatted(type, b64, type);
    }

    /** Exposed for tests and startup sanity checks. */
    public boolean staticMode() {
        return staticKey != null;
    }

    public List<String> audiences() {
        return List.of(cfg.audience());
    }
}
