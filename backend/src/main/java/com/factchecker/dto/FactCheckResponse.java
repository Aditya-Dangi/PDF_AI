package com.factchecker.dto;

import java.util.List;

public record FactCheckResponse(
        String messageId,
        String claimText,
        String claimType,
        boolean checkable,
        String verdict,
        double webConfidence,
        String summary,
        List<SourceDto> sources
) {
}
