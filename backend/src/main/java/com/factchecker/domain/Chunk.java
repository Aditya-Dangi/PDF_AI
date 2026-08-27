package com.factchecker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "chunks")
@Getter
@Setter
@NoArgsConstructor
public class Chunk {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String documentId;

    @Column(nullable = false)
    private int page;

    @Column(nullable = false)
    private int chunkOrder;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String text;

    /** JSON array of {x, y, width, height} rects in page point space, origin top-left, y-down (see RectDto). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String rectsJson;

    /** JSON array of floats - the embedding vector. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String embeddingJson;

    @Column(nullable = false)
    private int charCount;

    @Column(nullable = false)
    private boolean ocr = false;
}
