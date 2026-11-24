# NoteApp

Lightweight, production-oriented **backend** for team-collaborative note taking.

**Stack:** Java 21 · Quarkus 3.20 · PostgreSQL 16 (Row-Level Security) · Redis 7 (Streams + Pub/Sub + cache) · Lettuce · jose4j (JWT) · Hibernate/Panache · Flyway · Micrometer.

## Run locally

```bash
docker compose -f deploy/docker/docker-compose.yml up --build
# API:            http://localhost:8080
# OpenAPI + UI:   http://localhost:8080/q/openapi  /  /q/swagger-ui
# Health:         /q/health/live  /q/health/ready
# Metrics:        /q/metrics
```

Smoke test:

```bash
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"orgName":"Acme","username":"alice","email":"alice@acme.io","password":"s3cure-passw0rd","rememberMe":false}' \
  -c cookies.txt
curl -s localhost:8080/api/teams -b cookies.txt
```

Build + tests (hermetic — no containers needed):

```bash
mvn clean package       # 28 unit tests, Quarkus augmentation, quarkus-run.jar
```

## What is inside (requested focus → implementation)

| Concern | Where / how |
| --- | --- |
| Security | RLS on every tenant table (`V1__schema.sql`), tenant GUC bound per transaction (`TenantBoundInterceptor`), PBKDF2 passwords (210k iters), RS256 JWT (jose4j; static keys or IdP JWKS), HttpOnly cookie sessions + refresh rotation with **reuse detection** (family revocation), CSRF double-submit for cookie requests, security headers, rate-limited auth, AES-GCM webhook secrets at rest, non-root container |
| Retries | Outbox publish: exponential backoff + jitter → DLQ (`OutboxRelay`); consumer redelivery via Redis PEL + XCLAIM, `maxDeliveryAttempts` → DLQ (`StreamConsumerRunner`); webhook in-process retries then PEL/DLQ; Redis client auto-reconnect + keepalive |
| Multi-tenancy | `org_id` on every tenant row, RLS fail-closed (NULL GUC ⇒ zero rows), tenant only from verified token claims (never client headers), separate RLS-bound app role vs BYPASSRLS worker role |
| Data compression | GZIP note-version snapshots above threshold (rest, `bytea`), GZIP Redis cache values above threshold, HTTP gzip responses (`quarkus.http.compression-level`) |
| Indexing | Keyset composite indexes (partial, `WHERE deleted_at IS NULL`), GIN full-text index, unique functional indexes on `lower(username)/lower(email)` |
| Hash mapping | Guava **Bloom filter** availability pre-check (DB UNIQUE stays authoritative, startup rebuild via worker DS), HMAC-SHA256 **signed pagination cursors** (tamper-proof), SHA-256 version checksums, sha256-hashed refresh tokens |
| Rate-limiting | Redis sliding-window (ZSET + Lua, atomic) with fail-open guard, per-zone: register/login (IP), WS connect (user), read/write (user) — `RedisRateLimiter` |
| Idempotency | `Idempotency-Key` middleware: atomic claim upsert, request-hash conflict ⇒ 422, completed ⇒ replay stored response, in-flight ⇒ 409, stale-claim takeover, TTL retention |
| Queues | Transactional outbox → Redis Streams → consumer **groups** (one per handler) with parallel bounded worker pools, backpressure (`maxInFlight`), ack-after-process, PEL recovery, DLQ |
| Caching | Version-fenced cache-aside (INCR-versioned keys ⇒ no repopulation race), single-flight loaders (striped locks), central TTL registry, **event-driven invalidation** (`CacheEvictorHandler`) |
| Pub/sub | Redis Streams for durable events + Redis Pub/Sub for ephemeral realtime fan-out (`RealtimeFanoutHandler` → WS gateway channels) with parallel processing |
| Connection handling | Redis auto-reconnect + PING-on-activate + keepalive; WebSocket heartbeat, 60s token/session revalidation, close 4401 + client reconnect contract; graceful shutdown drains consumers (unacked entries stay in PEL) |
| Transaction wrappers | `@Transactional` + `@TenantBound` stack; outbox writer is `MANDATORY` (same-commit guarantee is structural, not a convention) |
| Upserts | `processed_events` (dedup), `idempotency_records` (claim), all `INSERT … ON CONFLICT` |
| No N+1 | List/search/comments/versions are single native queries with joins + DTO projections |
| Pagination | Keyset (seek) cursors everywhere; size caps; no OFFSET scans |
| WAL journaling | Tuned server-side in compose (`wal_compression`, checkpoint tuning); app relies on `synchronous_commit=on` — used where durability requires it (outbox, versions, sessions), not cargo-culted |
| Enterprise extras | OpenAPI + Swagger UI, liveness/readiness health (incl. Redis), Prometheus metrics (business + consumer + limiter), correlation ids in logs, append-only audit trail (event-sourced), HMAC-signed outbound webhooks, soft delete + version history + restore, HPA/PDB-ready K8s manifests, graceful degradation notes |

## Configuration

All configuration is environment-driven (`application.properties` + `notehive.*` mappings).

- `NOTEHIVE_CRYPTO_AES_KEY` — 32-byte base64 (webhook secrets at rest)
- `NOTEHIVE_CRYPTO_CURSOR_KEY` — 32-byte base64 (pagination cursor HMAC)
- JWT: `NOTEHIVE_JWT_KEY_MODE=jwks` + `NOTEHIVE_JWT_JWKS_URL` (SSO/Keycloak), or static PEM `NOTEHIVE_JWT_PUBLIC_KEY` / `NOTEHIVE_JWT_PRIVATE_KEY` (dev auto-generates a keypair and warns)
- Datasource credentials (RLS app role + BYPASSRLS worker role + migrator for Flyway)

See `docs/ARCHITECTURE.md` for design decisions and `docs/API.md` for the endpoint reference.
