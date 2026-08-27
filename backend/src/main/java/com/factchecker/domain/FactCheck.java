package com.factchecker.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fact_checks")
@Getter
@Setter
@NoArgsConstructor
public class FactCheck {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    private String messageId;

    @Lob
    @Column(nullable = false)
    private String claimText;

    @Column(nullable = false)
    private String claimType;

    @Column(nullable = false)
    private boolean checkable = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict;

    @Column(nullable = false)
    private double webConfidence;

    @Lob
    @Column(nullable = false)
    private String summary;

    /** JSON array of {url, title, snippet, stance, authorityTier, publishedDate}. */
    @Lob
    @Column(nullable = false)
    private String sourcesJson;

    /** How long FactCheckService.factCheck() took, wall-clock, for display in the UI. Nullable so
     *  adding this column via ddl-auto=update doesn't require backfilling pre-existing rows. */
    private Long durationMs;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
