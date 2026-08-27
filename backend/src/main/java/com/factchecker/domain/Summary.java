package com.factchecker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A plain summary of a selected passage or image region - deliberately separate from FactCheck:
 *  "Summarize" from the PDF selection toolbar is a direct summary of the selected content itself,
 *  not a claim-extraction/web-search/verdict result. See SummarizationService, Prompts.SUMMARIZE_SYSTEM. */
@Entity
@Table(name = "summaries")
@Getter
@Setter
@NoArgsConstructor
public class Summary {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    private String messageId;

    /** The text that was actually summarized - the selected text verbatim, or the OCR/vision-
     *  resolved text for an image region (see ImageQueryService). Shown in the UI so the user can
     *  see exactly what was summarized, not just the summary itself. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String sourceText;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String summaryText;

    /** How long the summarization call took, wall-clock, for display in the UI. Nullable so adding
     *  this column via ddl-auto=update doesn't require backfilling pre-existing rows. */
    private Long durationMs;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
