package com.notehive.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

/**
 * Central, environment-driven configuration. Every ${VAR:default} in
 * application.properties maps here; secrets never live in Git.
 */
public interface AppConfig {

    @ConfigMapping(prefix = "notehive.events")
    interface Events {
        @WithDefault("redis://localhost:6379/0")
        String redisUrl();

        @WithDefault("notehive:events")
        String stream();

        @WithDefault("notehive:events:dlq")
        String dlqStream();

        /** Stream MAXLEN (approximate trimming) — retention sized for replay. */
        @WithDefault("1000000")
        long maxlen();

        @WithDefault("100")
        int relayBatchSize();

        @WithDefault("2s")
        Duration relayPollInterval();

        @WithDefault("8")
        int maxPublishAttempts();

        /** Base for exponential backoff with jitter on publish failures. */
        @WithDefault("500ms")
        Duration backoffBase();

        @WithDefault("200")
        int consumerBatchSize();

        /** BLOCK time for XREADGROUP. */
        @WithDefault("2000ms")
        Duration pollBlockTime();

        /** Per-handler worker concurrency (bounded; never unbounded queues). */
        @WithDefault("8")
        int handlerConcurrency();

        /** Pause reads when in-flight work exceeds this (backpressure). */
        @WithDefault("256")
        int maxInFlight();

        /** Pending messages idle longer than this are claimable by other consumers. */
        @WithDefault("60s")
        Duration claimMinIdle();

        @WithDefault("10")
        int maxDeliveryAttempts();

        /** Instance-unique consumer name; must differ per replica. */
        @WithDefault("")
        String consumerId();
    }

    @ConfigMapping(prefix = "notehive.session")
    interface Session {
        @WithDefault("PT24H")
        Duration defaultTtl();

        @WithDefault("PT72H")
        Duration rememberMeTtl();

        /** Idle window: extended on refresh/use; absolute bound never moves. */
        @WithDefault("PT30M")
        Duration idleWindow();

        @WithDefault("PT15M")
        Duration accessTokenTtl();

        @WithDefault("5")
        int loginRateLimit();

        @WithDefault("PT1M")
        Duration loginRateWindow();

        @WithDefault("3")
        int registerRateLimit();

        @WithDefault("PT1H")
        Duration registerRateWindow();

        @WithDefault("20")
        int wsConnectRateLimit();

        @WithDefault("PT1M")
        Duration wsConnectRateWindow();

        @WithDefault("false")
        boolean secureCookies();
    }

    @ConfigMapping(prefix = "notehive.jwt")
    interface Jwt {
        /** Issuer/audience of tokens this service accepts and issues. */
        @WithDefault("notehive")
        String issuer();

        @WithDefault("notehive-api")
        String audience();

        /**
         * Verification keys: "static" uses jwt.public-key/jwt.private-key PEM
         * (app-issued tokens, dev default auto-generates if unset); "jwks"
         * fetches from jwks-url (Keycloak/SSO production mode).
         */
        @WithDefault("static")
        String keyMode();

        @WithDefault("")
        String publicKeyPem();

        @WithDefault("")
        String privateKeyPem();

        @WithDefault("")
        String jwksUrl();

        /** Ephemeral dev key material directory (auto-generated keys persisted here). */
        @WithDefault("target/jwt-keys")
        String devKeyDir();
    }

    @ConfigMapping(prefix = "notehive.cache")
    interface Cache {
        @WithDefault("PT10M")
        Duration note();

        @WithDefault("PT60S")
        Duration membership();

        @WithDefault("PT5M")
        Duration profile();

        @WithDefault("PT24H")
        Duration idempotency();

        @WithDefault("P30D")
        Duration versionCounter();

        /** compress cached values above this size (bytes) */
        @WithDefault("256")
        int gzipThreshold();
    }

    @ConfigMapping(prefix = "notehive.limits")
    interface Limits {
        @WithDefault("300")
        int readPerMinute();

        @WithDefault("60")
        int writePerMinute();

        @WithDefault("PT1M")
        Duration writeWindow();
    }

    @ConfigMapping(prefix = "notehive.notes")
    interface Notes {
        @WithDefault("50")
        int defaultPageSize();

        @WithDefault("100")
        int maxPageSize();

        /** gzip version snapshots above this size (bytes) */
        @WithDefault("512")
        int compressionThreshold();

        @WithDefault("1000000")
        int maxBodyChars();

        @WithDefault("8")
        int maxWebhookParallelism();
    }

    @ConfigMapping(prefix = "notehive.bloom")
    interface Bloom {
        @WithDefault("1000000")
        long expectedEntries();

        @WithDefault("0.01")
        double fpp();
    }

    @ConfigMapping(prefix = "notehive.webhooks")
    interface Webhooks {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("5s")
        Duration timeout();

        /** in-process delivery retries before the event falls back to PEL retry */
        @WithDefault("3")
        int maxAttempts();

        @WithDefault("1s")
        Duration backoffBase();
    }

    @ConfigMapping(prefix = "notehive.crypto")
    interface Crypto {
        /** Base64, 32 bytes: AES-GCM key for webhook secrets at rest. */
        @WithDefault("")
        String aesKey();

        /** Base64, 32 bytes: HMAC key for pagination cursors. */
        @WithDefault("")
        String cursorHmacKey();

        /** PBKDF2 iterations (OWASP 2023 recommends 600k for SHA-512; 210k SHA-256). */
        @WithDefault("210000")
        int pbkdf2Iterations();
    }
}
