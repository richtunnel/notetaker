package com.notehive.comment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Collaboration thread on a note. RLS-bound via org_id. */
@Entity
@Table(name = "comments")
public class NoteComment extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(name = "note_id", nullable = false)
    public UUID noteId;

    @Column(name = "author_id", nullable = false)
    public UUID authorId;

    @Column(nullable = false, columnDefinition = "varchar(8000)")
    public String body;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "deleted_at")
    public Instant deletedAt;
}
