# NoteHive — Architecture Notes

## 1. Shape

Single Quarkus module, strict package boundaries (lightweight, yet organized):

```
com.notehive
├── common/    errors, pagination cursor, compression, bloom, tenant context,
│              event envelope + schema validator
├── config/    typed ConfigMappings (events, sessions, cache TTLs, limits…)
├── infra/     Redis connection manager, cache store, rate limiter,
│              tenant interceptor, HTTP filters, CDI producers, health
├── auth/      users/orgs/sessions, PBKDF2, JWT (jose4j), cookie sessions,
│              CSRF, idempotency middleware, auth endpoints
├── team/      teams + membership + authorization (role ranking)
├── note/      notes, versions (compressed snapshots), search, keyset lists
├── comment/   note comments
├── event/     outbox entity/writer/relay, stream consumer runner/supervisor,
│              dedup DAO, backoff
├── event/handlers/  audit-logger, cache-evictor, realtime-fanout,
│                    webhook-delivery (+ endpoint entity, signer)
├── realtime/  WebSocket gateway + connection registry
└── health/    Redis readiness probe
```

## 2. Data flow

```
HTTP request
  → CorrelationIdFilter (MDC) → AuthFilter (JWT bearer | cookie + CSRF)
  → IdempotencyFilter (claim/replay) → rate limits (zones)
  → Resource → @Transactional @TenantBound service  (GUC bound: app.tenant_id)
       → SQL (RLS enforces tenant isolation, fail-closed)
       → outbox row (same transaction — MANDATORY writer)
  → COMMIT
  → OutboxRelay (poll → XADD stream → mark published; backoff+jitter → DLQ)
  → Stream consumer groups (parallel, bounded) → handlers
       → cache invalidation (version-fenced)
       → audit_logs (append-only)
       → Redis Pub/Sub → WebSocket gateways (fan-out, all replicas)
       → signed webhooks (retries → PEL → DLQ)
```

## 3. Multi-tenancy — why two database roles

- `notehive_app` (default datasource): every statement passes RLS. The GUC
  `app.tenant_id` is set `LOCAL` to each transaction by the interceptor, so
  pooled connections can never leak tenant context. NULL GUC ⇒ 0 rows ⇒
  fail-closed even if application code forgets a filter.
- `notehive_worker` (BYPASSRLS): only for cross-tenant maintenance (Bloom
  rebuild, retention). Never exposed to request paths.
- Auth tables (`organizations`, `users`, `sessions`) are not RLS-bound because
  login must find the user before a tenant exists; they are protected by
  unique constraints and application logic.

## 4. Idempotency and dedup — upsert everywhere

- `idempotency_records` PK `(org_id, idempotency_key)`: claim via
  `INSERT … ON CONFLICT DO NOTHING`. Same key + different hash ⇒ 422;
  completed ⇒ replay; fresh pending ⇒ 409; stale pending ⇒ takeover.
- `processed_events` PK `(event_id, group_name)`: at-least-once delivery from
  Redis Streams collapses to effectively-once handler execution
  (handlers are additionally written to be idempotent).

## 5. Caching — version-fenced invalidation

Readers seek `cache:{family}:{tenant}:{id}:v{version}` where version comes
from an INCR'd counter. Invalidate = INCR, so a slow writer that computed an
old key after invalidation writes to a dead key — the classic
delete/repopulate race is structurally impossible. TTLs are central
(`notehive.cache.*`) and values above a threshold are gzip+base64 stored.

## 6. Queues and retries

- Outbox relay: XADD **before** marking published (crash ⇒ re-publish ⇒
  dedup). Backoff = base·2^(n-1) with full jitter, capped; DLQ after
  `max-publish-attempts`.
- Consumers: XREADGROUP with BLOCK, bounded worker pool per handler,
  backpressure by pausing reads above `max-in-flight`, XACK strictly after
  success, XPENDING/XCLAIM recovery for crashed replicas, DLQ after
  `max-delivery-attempts`. Graceful shutdown stops fetching and drains.
- Redis Streams are authoritative (AOF `everysec` in compose); Pub/Sub only
  carries ephemeral frames where loss is harmless.

## 7. Connections

- Lettuce: `autoReconnect`, `pingBeforeActivateConnection`, TCP keepalive;
  commands during a disconnect are rejected fast (fail-fast beats hanging).
- Agroal: bounded pool (20 app / 5 worker), leak detection, validation query;
  replicas × pool ≤ PostgreSQL `max_connections` — size accordingly.
- WebSocket: heartbeat ping/pong, 60s revalidation of the presenting token
  AND server-side session; 4401 close code; clients reconnect with backoff
  and resubscribe (server re-authorizes every subscription).

## 8. What was deliberately NOT used

- No OFFSET pagination anywhere (keyset only, HMAC-signed cursors).
- No client-supplied tenant headers (tenant lives in verified claims only).
- No cache-before-commit patterns (invalidation is event-driven, after commit).
- No autocommit business writes (outbox writer is `MANDATORY`).
- No secrets in Git (compose/k8s files are templates with placeholders).

## 9. Scaling notes

- App replicas are stateless; consumer identity = pod name (unique per
  replica). Scale out for throughput; each replica gets a share of every
  consumer group.
- Connection math: `replicas × 25 pools ≤ PG max_connections` (compose sets 120).
- HPA on CPU with a 5-minute scale-down window avoids PEL thrash.
