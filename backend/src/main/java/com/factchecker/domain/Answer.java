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

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
