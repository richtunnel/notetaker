package com.notehive.note;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Note aggregate. Optimistic locking via explicit version column (compare-and-set). */
@Entity
@Table(name = "notes")
public class Note extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(name = "team_id", nullable = false)
    public UUID teamId;

    @Column(nullable = false)
    public String title;

    @Column(nullable = false, columnDefinition = "text")
    public String body = "";

    @Column(nullable = false)
    public String format = "markdown";

    @Column(nullable = false)
    public int version = 1;

    @Column(name = "created_by", nullable = false)
    public UUID createdBy;

    @Column(name = "updated_by", nullable = false)
    public UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    public Instant deletedAt;

    public static Optional<Note> findActive(UUID tenantId, UUID noteId) {
        return Note.<Note>find("orgId = ?1 and id = ?2 and deletedAt is null",
                tenantId, noteId).firstResultOptional();
    }
}
