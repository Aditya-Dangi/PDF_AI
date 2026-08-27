package com.factchecker.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class Document {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String storagePath;

    @Column(nullable = false)
    private int pageCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PROCESSING;

    private String failureReason;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'NONE'")
    @Column(nullable = false)
    private AuditStatus auditStatus = AuditStatus.NONE;

    @ColumnDefault("0")
    @Column(nullable = false)
    private int auditClaimsDetected = 0;

    private String auditFailureReason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
