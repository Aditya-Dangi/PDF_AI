package com.factchecker.domain;

import jakarta.persistence.*;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Cached structural rendering of a document (see DocumentStructureService) - the Markdown view and
 * the typed block list behind the workspace's Text pane.
 *
 * <p>Computed on first request rather than during ingestion: that way existing documents work with
 * no migration or backfill, and ingestion (already the slowest part of upload) does not get slower
 * for a view most users open once, if ever.
 */
@Entity
@Table(name = "document_structures")
@Getter
@Setter
@NoArgsConstructor
public class DocumentStructure {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    private String documentId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Length.LONG32)
    private String markdown;

    /** JSON array of {page, type, headingLevel, text, rects}. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Length.LONG32)
    private String blocksJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
