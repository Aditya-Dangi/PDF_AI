package com.factchecker.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "answers")
@Getter
@Setter
@NoArgsConstructor
public class Answer {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    private String messageId;

    @Lob
    @Column(nullable = false)
    private String documentClaim;

    @Lob
    @Column(nullable = false)
    private String explanation;

    @Column(nullable = false)
    private boolean insufficientContext = false;

    @Column(nullable = false)
    private double retrievalConfidence;

    @Column(nullable = false)
    private double fidelityConfidence;

    /** JSON array of {chunkId, page, rects, text, similarity}. */
    @Lob
    @Column(nullable = false)
    private String evidenceJson;

    /** How long RagService.answer() took, wall-clock, for display in the UI. Nullable so adding
     *  this column via ddl-auto=update doesn't require backfilling rows from before this field
     *  existed - the frontend just omits the badge when it's null. */
    private Long durationMs;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
