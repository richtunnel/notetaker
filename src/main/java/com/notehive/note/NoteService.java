package com.notehive.note;

import com.notehive.common.ApiException;
import com.notehive.common.CursorCodec;
import com.notehive.common.ErrorCode;
import com.notehive.common.EventEnvelope;
import com.notehive.common.EventTypes;
import com.notehive.common.Json;
import com.notehive.common.Page;
import com.notehive.common.TenantBound;
import com.notehive.config.AppConfig;
import com.notehive.event.EventPublisher;
import com.notehive.infra.RedisCacheStore;
import com.notehive.team.TeamService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Note lifecycle: create / optimistic update / soft delete / restore / read
 * (cache-aside) / keyset lists / full-text search / version history.
 *
 * Performance and correctness notes:
 *  - every mutation is one transaction that ALSO appends the outbox event
 *    (transaction wrapper + same-commit guarantee);
 *  - updates use compare-and-set on the version column (lost updates are
 *    rejected, never silently overwritten);
 *  - list/search/versions are single native queries with joins — no N+1;
 *  - lists use HMAC-signed keyset cursors (no OFFSET scans);
 *  - reads go through the version-fenced Redis cache; invalidation is
 *    event-driven (CacheEvictorHandler).
 */
@ApplicationScoped
public class NoteService {

    @Inject EventPublisher events;
    @Inject EntityManager em;
    @Inject TeamService teams;
    @Inject RedisCacheStore cache;
    @Inject AppConfig.Notes notesCfg;
    @Inject AppConfig.Cache cacheCfg;
    @Inject CursorCodec cursors;
    @Inject MeterRegistry metrics;

    private Counter createdCounter;
    private Counter updatedCounter;

    @PostConstruct
    void initMetrics() {
        createdCounter = Counter.builder("notehive_notes_created_total").register(metrics);
        updatedCounter = Counter.builder("notehive_notes_updated_total").register(metrics);
    }

    public record NoteView(UUID id, UUID teamId, String teamName, String title, String body,
                           String format, int version, UUID createdBy, UUID updatedBy,
                           String updatedByName, String createdAt, String updatedAt,
                           boolean deleted) {}

    public record CreateRequest(UUID teamId, String title, String body, String format) {}

    public record UpdateRequest(Integer expectedVersion, String title, String body) {}

    // ------------------------------------------------------------------ writes

    @Transactional
    @TenantBound
    public NoteView create(UUID tenantId, UUID userId, CreateRequest req) {
        if (req == null || req.teamId() == null) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "teamId required");
        }
        if (req.title() == null || req.title().isBlank() || req.title().length() > 200) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "title required (max 200)");
        }
        String body = req.body() == null ? "" : req.body();
        if (body.length() > notesCfg.maxBodyChars()) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED,
                    "body exceeds " + notesCfg.maxBodyChars() + " characters");
        }
        String format = "markdown".equals(req.format()) || "plain".equals(req.format())
                ? req.format() : "markdown";

        teams.requireRole(tenantId, userId, req.teamId(), "editor");

        UUID noteId = UUID.randomUUID();
        int inserted = em.createNativeQuery("""
                INSERT INTO notes (id, org_id, team_id, title, body, format, version,
                                   created_by, updated_by, created_at, updated_at)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, 1, ?7, ?7, now(), now())
                """)
                .setParameter(1, noteId).setParameter(2, tenantId)
                .setParameter(3, req.teamId()).setParameter(4, req.title().trim())
                .setParameter(5, body).setParameter(6, format).setParameter(7, userId)
                .executeUpdate();

        em.persist(NoteVersion.of(tenantId, noteId, 1, req.title().trim(), body, userId,
                notesCfg.compressionThreshold()));

        events.publish(EventEnvelope.create(EventTypes.NOTE_CREATED, tenantId, noteId, null,
                Json.objectNode()
                        .put("noteId", noteId.toString())
                        .put("teamId", req.teamId().toString())
                        .put("title", req.title().trim())
                        .put("version", 1)));
        createdCounter.increment();
        return view(noteId);
    }

    @Transactional
    @TenantBound
    public NoteView update(UUID tenantId, UUID userId, UUID noteId, UpdateRequest req) {
        if (req == null) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "body required");
        }
        if (req.title() != null && (req.title().isBlank() || req.title().length() > 200)) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED, "title max 200");
        }
        String body = req.body();
        if (body != null && body.length() > notesCfg.maxBodyChars()) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED,
                    "body exceeds " + notesCfg.maxBodyChars() + " characters");
        }
        teams.requireRole(tenantId, userId, teamOf(tenantId, noteId), "editor");

        // optimistic compare-and-set: only the holder of the expected version wins
        int updated = em.createNativeQuery("""
                UPDATE notes
                   SET title = COALESCE(?2, title),
                       body = COALESCE(?3, body),
                       version = version + 1,
                       updated_by = ?4,
                       updated_at = now()
                 WHERE id = ?1
                   AND org_id = current_setting('app.tenant_id', true)::uuid
                   AND version = ?5
                   AND deleted_at IS NULL
                """)
                .setParameter(1, noteId)
                .setParameter(2, req.title() == null ? null : req.title().trim())
                .setParameter(3, body)
                .setParameter(4, userId)
                .setParameter(5, req.expectedVersion() == null ? 0 : req.expectedVersion())
                .executeUpdate();
        if (updated == 0) {
            throw new ApiException.Conflict(ErrorCode.VERSION_CONFLICT,
                    "Version conflict — reload the note and reapply your changes");
        }

        Note note = Note.findActive(tenantId, noteId)
                .orElseThrow(() -> new ApiException.Internal("note vanished after CAS", null));
        em.persist(NoteVersion.of(tenantId, noteId, note.version, note.title, note.body,
                userId, notesCfg.compressionThreshold()));

        events.publish(EventEnvelope.create(EventTypes.NOTE_UPDATED, tenantId, noteId, null,
                Json.objectNode()
                        .put("noteId", noteId.toString())
                        .put("teamId", note.teamId.toString())
                        .put("version", note.version)));
        updatedCounter.increment();
        return view(noteId);
    }

    @Transactional
    @TenantBound
    public void delete(UUID tenantId, UUID userId, UUID noteId) {
        UUID teamId = teamOf(tenantId, noteId); // capture BEFORE the soft delete
        teams.requireRole(tenantId, userId, teamId, "editor");
        int updated = em.createNativeQuery("""
                UPDATE notes SET deleted_at = now(), version = version + 1, updated_by = ?2
                WHERE id = ?1 AND org_id = current_setting('app.tenant_id', true)::uuid
                  AND deleted_at IS NULL
                """)
                .setParameter(1, noteId).setParameter(2, userId)
                .executeUpdate();
        if (updated == 0) {
            throw new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found");
        }
        events.publish(EventEnvelope.create(EventTypes.NOTE_DELETED, tenantId, noteId, null,
                Json.objectNode().put("noteId", noteId.toString())
                        .put("teamId", teamId.toString())));
    }

    @Transactional
    @TenantBound
    public NoteView restore(UUID tenantId, UUID userId, UUID noteId) {
        teams.requireRole(tenantId, userId, teamOf(tenantId, noteId, true), "editor");
        int updated = em.createNativeQuery("""
                UPDATE notes SET deleted_at = NULL, version = version + 1, updated_by = ?2,
                                 updated_at = now()
                WHERE id = ?1 AND org_id = current_setting('app.tenant_id', true)::uuid
                """)
                .setParameter(1, noteId).setParameter(2, userId)
                .executeUpdate();
        if (updated == 0) {
            throw new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found");
        }
        events.publish(EventEnvelope.create(EventTypes.NOTE_RESTORED, tenantId, noteId, null,
                Json.objectNode().put("noteId", noteId.toString())));
        return view(noteId);
    }

    /** Restore the CONTENT of a historical version as a NEW version. */
    @Transactional
    @TenantBound
    public NoteView restoreVersion(UUID tenantId, UUID userId, UUID noteId, int version) {
        teams.requireRole(tenantId, userId, teamOf(tenantId, noteId), "editor");
        NoteVersion snapshot = NoteVersion.<NoteVersion>find(
                        "orgId = ?1 and noteId = ?2 and version = ?3", tenantId, noteId, version)
                .firstResultOptional()
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.NOTE_VERSION_NOT_FOUND,
                        "Version not found"));
        int expected = currentVersion(tenantId, noteId);
        return update(tenantId, userId, noteId, new UpdateRequest(expected,
                snapshot.title, snapshot.body()));
    }

    // ------------------------------------------------------------------ reads

    @Transactional
    @TenantBound
    public NoteView get(UUID tenantId, UUID userId, UUID noteId) {
        Note note = Note.findActive(tenantId, noteId)
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found"));
        teams.requireMember(tenantId, userId, note.teamId);
        return cache.getOrLoad("note", tenantId.toString(), noteId.toString(), NoteView.class,
                cacheCfg.note(), () -> view(noteId));
    }

    /** Keyset pagination: seek by (updated_at, id) DESC with an HMAC-signed cursor. */
    @Transactional
    @TenantBound
    public Page<NoteView> list(UUID tenantId, UUID userId, UUID teamId,
                               String cursorRaw, Integer limit) {
        int pageSize = pageSize(limit);
        CursorCodec.Cursor cursor = cursors.decode(cursorRaw);
        if (cursor != null && teamId == null) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED,
                    "teamId is required when paging");
        }
        if (teamId != null) {
            teams.requireMember(tenantId, userId, teamId);
        }
        var query = em.createNativeQuery("""
                SELECT n.id, n.title, n.version, n.updated_at, n.created_at,
                       u.display_name, t.name AS team_name
                FROM notes n
                JOIN teams t ON t.id = n.team_id
                LEFT JOIN users u ON u.id = n.updated_by
                WHERE n.org_id = current_setting('app.tenant_id', true)::uuid
                  AND n.deleted_at IS NULL
                  AND (CAST(?1 AS uuid) IS NULL OR n.team_id = ?1)
                  AND (CAST(?2 AS timestamptz) IS NULL OR (n.updated_at, n.id) < (?2, CAST(?3 AS uuid)))
                ORDER BY n.updated_at DESC, n.id DESC
                LIMIT ?4
                """)
                .setParameter(1, teamId)
                .setParameter(2, cursor == null ? null : microsToInstant(cursor.t()))
                .setParameter(3, cursor == null ? null : cursor.i())
                .setParameter(4, pageSize + 1);
        List<NoteView> items = mapViews(query.getResultList());
        boolean hasMore = items.size() > pageSize;
        if (hasMore) {
            items = items.subList(0, pageSize);
        }
        String next = null;
        if (hasMore && !items.isEmpty()) {
            var last = items.get(items.size() - 1);
            next = cursors.encode(new CursorCodec.Cursor(updatedAtMicros(last), last.id()));
        }
        return Page.of(items, next, hasMore);
    }

    /** Full-text search: websearch_to_tsquery is injection-safe by design. */
    @Transactional
    @TenantBound
    public Page<NoteView> search(UUID tenantId, UUID userId, UUID teamId,
                                 String q, Integer limit) {
        if (q == null || q.isBlank()) {
            return Page.of(List.of(), null, false);
        }
        int pageSize = Math.min(pageSize(limit), 50);
        if (teamId != null) {
            teams.requireMember(tenantId, userId, teamId);
        }
        var query = em.createNativeQuery("""
                SELECT n.id, n.title, n.version, n.updated_at, n.created_at,
                       u.display_name, t.name AS team_name,
                       ts_rank(n.search, websearch_to_tsquery('simple', ?2)) AS rank
                FROM notes n
                JOIN teams t ON t.id = n.team_id
                LEFT JOIN users u ON u.id = n.updated_by
                WHERE n.org_id = current_setting('app.tenant_id', true)::uuid
                  AND n.deleted_at IS NULL
                  AND n.search @@ websearch_to_tsquery('simple', ?2)
                  AND (CAST(?1 AS uuid) IS NULL OR n.team_id = ?1)
                ORDER BY rank DESC, n.updated_at DESC
                LIMIT ?3
                """)
                .setParameter(1, teamId)
                .setParameter(2, q.trim())
                .setParameter(3, pageSize);
        return Page.of(mapViews(query.getResultList()), null, false);
    }

    /** Version history (keyset by version DESC). */
    @Transactional
    @TenantBound
    public Page<VersionView> versions(UUID tenantId, UUID userId, UUID noteId,
                                      Integer cursorVersion, Integer limit) {
        int pageSize = Math.min(pageSize(limit), 100);
        Note note = Note.findActive(tenantId, noteId)
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found"));
        teams.requireMember(tenantId, userId, note.teamId);
        var query = em.createNativeQuery("""
                SELECT v.version, v.title, v.original_size, v.compressed, v.checksum,
                       v.created_at, u.display_name, v.version_id
                FROM note_versions v
                LEFT JOIN users u ON u.id = v.author_id
                WHERE v.org_id = current_setting('app.tenant_id', true)::uuid
                  AND v.note_id = ?1
                  AND (CAST(?2 AS int) IS NULL OR v.version < ?2)
                ORDER BY v.version DESC
                LIMIT ?3
                """)
                .setParameter(1, noteId)
                .setParameter(2, cursorVersion)
                .setParameter(3, pageSize + 1);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        String next = hasMore && !rows.isEmpty()
                ? cursors.encode(new CursorCodec.Cursor(
                        ((Number) rows.get(rows.size() - 1)[0]).longValue(), noteId))
                : null;
        List<VersionView> items = rows.stream().map(r -> new VersionView(
                ((Number) r[0]).intValue(), (String) r[1], ((Number) r[2]).intValue(),
                (Boolean) r[3], (String) r[4], r[5].toString(), (String) r[6])).toList();
        return Page.of(items, next, hasMore);
    }

    public record VersionView(int version, String title, int originalSize, boolean compressed,
                              String checksum, String createdAt, String authorName) {}

    /** Authoritative version (used by clients for optimistic writes). */
    @Transactional
    @TenantBound
    public int currentVersion(UUID tenantId, UUID noteId) {
        Note note = Note.findActive(tenantId, noteId)
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found"));
        return note.version;
    }

    // ------------------------------------------------------------------ helpers

    /** Public team-of-note lookup for the WebSocket gateway's subscription auth.
     *  Self-sufficient: binds the GUC itself because the gateway has no request
     *  context (WebSocket threads). */
    @Transactional
    public UUID teamOfPublic(UUID tenantId, UUID noteId) {
        bindGuc(tenantId);
        return teamOf(tenantId, noteId, true);
    }

    private void bindGuc(UUID tenantId) {
        em.createNativeQuery("SELECT set_config('app.tenant_id', ?1, true)")
                .setParameter(1, tenantId.toString())
                .getSingleResult();
    }

    private UUID teamOf(UUID tenantId, UUID noteId) {
        return teamOf(tenantId, noteId, false);
    }

    @SuppressWarnings("unchecked")
    private UUID teamOf(UUID tenantId, UUID noteId, boolean includeDeleted) {
        // single-column native query → List<UUID> (not Object[])
        List<UUID> rows = em.createNativeQuery(
                        "SELECT team_id FROM notes WHERE id = ?1 AND org_id = " +
                        "current_setting('app.tenant_id', true)::uuid " +
                        (includeDeleted ? "" : "AND deleted_at IS NULL"))
                .setParameter(1, noteId)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found");
        }
        return rows.get(0);
    }

    private NoteView view(UUID noteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT n.id, n.team_id, t.name, n.title, n.body, n.format, n.version,
                       n.created_by, n.updated_by, u.display_name, n.created_at, n.updated_at,
                       n.deleted_at
                FROM notes n
                JOIN teams t ON t.id = n.team_id
                LEFT JOIN users u ON u.id = n.updated_by
                WHERE n.id = ?1 AND n.org_id = current_setting('app.tenant_id', true)::uuid
                """)
                .setParameter(1, noteId)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found");
        }
        Object[] r = rows.get(0);
        return new NoteView((UUID) r[0], (UUID) r[1], (String) r[2], (String) r[3],
                (String) r[4], (String) r[5], ((Number) r[6]).intValue(), (UUID) r[7],
                (UUID) r[8], (String) r[9], instant(r[10]), instant(r[11]), r[12] != null);
    }

    private List<NoteView> mapViews(List<?> rawRows) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) rawRows;
        return rows.stream().map(r -> new NoteView(
                (UUID) r[0], null, (String) r[6], (String) r[1], null, null,
                ((Number) r[2]).intValue(), null, null, (String) r[5],
                instant(r[3]), instant(r[4]), false)).toList();
    }

    /** Native timestamptz results arrive as OffsetDateTime (or Timestamp in rare drivers). */
    private static String instant(Object value) {
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        return value.toString();
    }

    private int pageSize(Integer limit) {
        if (limit == null || limit <= 0) return notesCfg.defaultPageSize();
        return Math.min(limit, notesCfg.maxPageSize());
    }

    private long updatedAtMicros(NoteView v) {
        return instantToMicros(java.time.Instant.parse(v.updatedAt()));
    }

    private static java.time.Instant microsToInstant(long micros) {
        return java.time.Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
    }

    private static long instantToMicros(java.time.Instant i) {
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1_000L;
    }
}
