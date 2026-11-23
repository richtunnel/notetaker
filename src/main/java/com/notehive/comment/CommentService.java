package com.notehive.comment;

import com.notehive.common.ApiException;
import com.notehive.common.ErrorCode;
import com.notehive.common.EventEnvelope;
import com.notehive.common.EventTypes;
import com.notehive.common.Json;
import com.notehive.common.TenantBound;
import com.notehive.event.EventPublisher;
import com.notehive.note.Note;
import com.notehive.team.TeamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/** Comment service. Single-query listing with author names (no N+1). */
@ApplicationScoped
public class CommentService {

    @Inject EventPublisher events;
    @Inject EntityManager em;
    @Inject TeamService teams;

    public record CommentView(UUID id, UUID noteId, UUID authorId, String authorName,
                              String body, String createdAt) {}

    @Transactional
    @TenantBound
    public CommentView create(UUID tenantId, UUID userId, UUID noteId, String body) {
        if (body == null || body.isBlank() || body.length() > 8000) {
            throw new ApiException.Validation(ErrorCode.VALIDATION_FAILED,
                    "body required (max 8000 chars)");
        }
        Note note = Note.findActive(tenantId, noteId)
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found"));
        teams.requireMember(tenantId, userId, note.teamId);

        NoteComment comment = new NoteComment();
        comment.id = UUID.randomUUID();
        comment.orgId = tenantId;
        comment.noteId = noteId;
        comment.authorId = userId;
        comment.body = body.trim();
        comment.persist();

        events.publish(EventEnvelope.create(EventTypes.COMMENT_CREATED, tenantId, noteId, null,
                Json.objectNode()
                        .put("commentId", comment.id.toString())
                        .put("noteId", noteId.toString())
                        .put("teamId", note.teamId.toString())
                        .put("authorId", userId.toString())));
        return view(comment.id);
    }

    /** ONE query joins authors — no N+1. */
    @Transactional
    @TenantBound
    @SuppressWarnings("unchecked")
    public List<CommentView> list(UUID tenantId, UUID userId, UUID noteId) {
        Note note = Note.findActive(tenantId, noteId)
                .orElseThrow(() -> new ApiException.NotFound(ErrorCode.NOTE_NOT_FOUND, "Note not found"));
        teams.requireMember(tenantId, userId, note.teamId);
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.id, c.note_id, c.author_id, u.display_name, c.body, c.created_at
                FROM comments c
                LEFT JOIN users u ON u.id = c.author_id
                WHERE c.org_id = current_setting('app.tenant_id', true)::uuid
                  AND c.note_id = ?1 AND c.deleted_at IS NULL
                ORDER BY c.created_at ASC
                LIMIT 500
                """)
                .setParameter(1, noteId)
                .getResultList();
        return rows.stream().map(r -> new CommentView(
                (UUID) r[0], (UUID) r[1], (UUID) r[2], (String) r[3], (String) r[4],
                instant(r[5]))).toList();
    }

    private static String instant(Object value) {
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        return value.toString();
    }

    private CommentView view(UUID commentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.id, c.note_id, c.author_id, u.display_name, c.body, c.created_at
                FROM comments c
                LEFT JOIN users u ON u.id = c.author_id
                WHERE c.id = ?1 AND c.org_id = current_setting('app.tenant_id', true)::uuid
                """)
                .setParameter(1, commentId)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ApiException.Internal("comment vanished after insert", null);
        }
        Object[] r = rows.get(0);
        return new CommentView((UUID) r[0], (UUID) r[1], (UUID) r[2], (String) r[3],
                (String) r[4], instant(r[5]));
    }
}
