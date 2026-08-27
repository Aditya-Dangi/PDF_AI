package com.factchecker.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A single atomic, independently-verified factual claim. Unlike Answer/FactCheck (which are tied
 * 1:1 to a chat message), a Claim can exist without a message - document audit mode detects and
 * verifies claims directly from the document's chunks. messageId links it back to the chat answer
 * it was decomposed from, when applicable; sourceClaimId links a challenge-mode re-verification
 * back to the original claim it re-examined.
 */
@Entity
@Table(name = "claims")
@Getter
@Setter
@NoArgsConstructor
public class Claim {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String documentId;

    private String messageId;

    private String sourceClaimId;

    @Lob
    @Column(nullable = false)
    private String claimText;

    @Column(nullable = false)
    private String claimType;

    @Column(nullable = false)
    private boolean timeSensitive = false;

    @Column(nullable = false)
    private boolean checkable = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimMode mode = ClaimMode.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict;

    @Column(nullable = false)
    private double retrievalConfidence;

    @Column(nullable = false)
    private double fidelityConfidence;

    @Column(nullable = false)
    private double webConfidence;

    @Column(nullable = false)
    private double sourceIndependenceScore;

    @Column(nullable = false)
    private int independentSourceCount;

    @Column(nullable = false)
    private int rawSourceCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemporalStatus temporalStatus;

    /** JSON array of EvidenceDto - the document passage(s) this claim was grounded in. */
    @Lob
    @Column(nullable = false)
    private String evidenceJson;

    /** JSON array of SourceDto with stance SUPPORTS/MIXED. */
    @Lob
    @Column(nullable = false)
    private String supportSourcesJson;

    /** JSON array of SourceDto with stance CONTRADICTS (kept separate from supportSourcesJson so the UI can show disagreement explicitly). */
    @Lob
    @Column(nullable = false)
    private String counterSourcesJson;

    @Lob
    @Column(nullable = false)
    private String rationale;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
