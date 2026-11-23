# NoteHive — API Reference

Base URL: `/api`. All responses are JSON. Errors use a unified body:

```json
{ "code": "NOTE_NOT_FOUND", "title": "Not found", "status": 404,
  "detail": "Note not found", "traceId": "…", "correlationId": "…" }
```

## Authentication

Two equivalent mechanisms (both RS256 JWT):

1. **Bearer** — `Authorization: Bearer <access token>` (API clients, services).
2. **Cookies** — `notehive_at` (HttpOnly access token) after login/register.
   Cookie-authenticated **unsafe methods** (POST/PUT/PATCH/DELETE) require the
   CSRF double-submit header `X-CSRF-Token` matching the readable `csrf` cookie.

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | – | Creates org + user + “General” team + session. Rate-limited per IP (3/h). Body: `{orgName, username, email, password, rememberMe}` |
| POST | `/api/auth/login` | – | Body: `{emailOrUsername, password, rememberMe}`. Rate-limited per IP (5/min) |
| POST | `/api/auth/refresh` | – | Rotates the refresh token (reuse ⇒ whole session family revoked). Body: `{refreshToken}` |
| POST | `/api/auth/logout` | – | Revokes the presented refresh token |
| GET | `/api/auth/me` | ✓ | Current principal (`userId`, `tenantId`, `role`) |
| GET | `/api/auth/username-available?value=` | – | Bloom pre-check + DB verification |
| GET | `/api/auth/email-available?value=` | – | DB verification |

Response (register/login/refresh) sets cookies and returns
`{accessToken, refreshToken, sessionId, userId, tenantId, expiresIn}`.

## Teams

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/teams` | – (creates owner membership) | `{name}` |
| GET | `/api/teams` | – | Teams of the caller, single joined query |
| POST | `/api/teams/{id}/members` | editor+ | `{username, role}` — role owner\|editor\|viewer |
| DELETE | `/api/teams/{id}/members/{userId}` | editor+ | Owners cannot be removed |

## Notes

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/notes` | editor+ | `{teamId, title, body, format}`. Idempotency supported |
| GET | `/api/notes?teamId=&cursor=&limit=` | member | Keyset page (`nextCursor`, `hasMore`), limit ≤ 100 |
| GET | `/api/notes/search?q=&teamId=&limit=` | member | Full-text (`websearch_to_tsquery`), ranked |
| GET | `/api/notes/{id}` | member | Cache-aside read (version-fenced) |
| PUT | `/api/notes/{id}` | editor+ | `{expectedVersion, title?, body?}` — CAS on version ⇒ 409 `VERSION_CONFLICT` on lost races |
| DELETE | `/api/notes/{id}` | editor+ | Soft delete (restorable) |
| POST | `/api/notes/{id}/restore` | editor+ | Undelete |
| GET | `/api/notes/{id}/versions?cursor=&limit=` | member | Version history (keyset by version) |
| POST | `/api/notes/{id}/restore/{version}` | editor+ | Restores content as a NEW version |
| GET | `/api/notes/{id}/version` | member | Authoritative version for optimistic writes |

## Comments

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/notes/{id}/comments` | member | `{body}` (≤ 8000 chars) |
| GET | `/api/notes/{id}/comments` | member | Single joined query |

## Webhooks (outbound, HMAC-signed)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/webhooks` | `{url, eventTypes[]}` — secret returned ONCE. Delivery headers: `X-NoteHive-Event`, `X-NoteHive-Timestamp`, `X-NoteHive-Signature` = `base64(HMAC-SHA256(secret, ts + "." + body))`. Verify and reject timestamps older than 5 min |
| GET | `/api/webhooks` | Registered endpoints |
| DELETE | `/api/webhooks/{id}` | Deactivate |

## Idempotency (all unsafe /api methods)

Send `Idempotency-Key: <8–120 chars>`.

- New key → executes; response snapshot stored (24 h).
- Same key + same payload → **replayed** response (`Idempotency-Replayed: true`).
- Same key + different payload → `422 IDEMPOTENCY_CONFLICT`.
- Concurrent in-flight duplicate → `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`.

## WebSocket — `/realtime`

Handshake: bearer token or `notehive_at` cookie (verified before upgrade;
rate-limited per user).

Client frames (JSON):

```json
{"type":"subscribe","channel":"team:UUID"}
{"type":"subscribe","channel":"note:UUID"}
{"type":"unsubscribe","channel":"team:UUID"}
{"type":"heartbeat"}
```

Server frames: `hello`, `subscribed`, `unsubscribed`, `pong`, `error`, and
event frames `{type:"note.updated", eventId, aggregateId, occurredAt, payload}`.

Rules: subscriptions are authorized server-side (team membership, note → team
membership); heartbeat every ≤ 30 s; token/session revalidated every 60 s —
expired sockets are closed with code **4401**; reconnect with exponential
backoff and resubscribe.

## Operations

- `GET /q/health/live`, `/q/health/ready` (includes Redis readiness)
- `GET /q/metrics` — Prometheus (`notehive_events_*`, `notehive_outbox_*`,
  `notehive_webhooks_*`, `notehive_audit_*`, JVM/DB/HTTP)
- `GET /q/openapi`, `GET /q/swagger-ui`

Rate limits (configurable, `notehive.limits.*` / `notehive.session.*`):
register 3/h/IP, login 5/min/IP, WS connect 20/min/user, reads 300/min/user,
writes 60/min/user. 429 responses carry `Retry-After`.
