package com.factchecker.dto;

import com.factchecker.domain.Document;

import java.time.Instant;

public record DocumentResponse(
        String id,
        String filename,
        int pageCount,
        String status,
        String failureReason,
        Instant createdAt
) {
    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getFilename(),
                doc.getPageCount(),
                doc.getStatus().name(),
                doc.getFailureReason(),
                doc.getCreatedAt()
        );
    }
}
