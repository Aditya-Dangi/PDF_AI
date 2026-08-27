package com.factchecker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Caches web-search + classification results by a hash of the normalized claim text, to avoid re-running search/LLM calls. */
@Entity
@Table(name = "web_cache")
@Getter
@Setter
@NoArgsConstructor
public class WebCache {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    private String claimHash;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String resultJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
