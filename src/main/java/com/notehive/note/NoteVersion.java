package com.notehive.note;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable version snapshot. Body is stored either raw (small) or
 * gzip-deflated (compressed = true, above the configured threshold) — data
 * compression at rest. checksum = sha256(raw body) for integrity verification.
 */
@Entity
@Table(name = "note_versions")
public class NoteVersion extends PanacheEntityBase {

    @Id
    @Column(nullable = false)
    public UUID id;

    @Column(name = "org_id", nullable = false)
    public UUID orgId;

    @Column(name = "note_id", nullable = false)
    public UUID noteId;

    @Column(nullable = false)
    public int version;

    @Column(nullable = false)
    public String title;

    @Column(name = "body_bytes", nullable = false, columnDefinition = "bytea")
    public byte[] bodyBytes;

    @Column(nullable = false)
    public boolean compressed;

    @Column(name = "original_size", nullable = false)
    public int originalSize;

    @Column(nullable = false, columnDefinition = "char(64)")
    public String checksum;

    @Column(name = "author_id", nullable = false)
    public UUID authorId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public String body() {
        byte[] raw = com.notehive.common.Compressor.gunzip(bodyBytes);
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static NoteVersion of(UUID orgId, UUID noteId, int version, String title,
                                 String body, UUID authorId, int compressionThreshold) {
        byte[] raw = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        NoteVersion v = new NoteVersion();
        v.id = UUID.randomUUID();
        v.orgId = orgId;
        v.noteId = noteId;
        v.version = version;
        v.title = title;
        v.originalSize = raw.length;
        v.checksum = com.notehive.common.CursorCodec.sha256Hex(raw);
        v.authorId = authorId;
        if (raw.length > compressionThreshold) {
            v.bodyBytes = com.notehive.common.Compressor.gzip(raw);
            v.compressed = true;
        } else {
            v.bodyBytes = raw;
            v.compressed = false;
        }
        return v;
    }
}
