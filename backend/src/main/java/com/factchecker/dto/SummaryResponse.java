package com.factchecker.dto;

public record SummaryResponse(
        String messageId,
        String sourceText,
        String summaryText,
        Long durationMs
) {
}
