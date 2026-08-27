package com.factchecker.dto;

import java.time.Instant;
import java.util.List;

public record ClaimResponse(
        String id,
        String documentId,
        String messageId,
        String sourceClaimId,
        String claimText,
        String claimType,
        boolean timeSensitive,
        boolean checkable,
        String mode,
        String verdict,
        double retrievalConfidence,
        double fidelityConfidence,
        double webConfidence,
        double sourceIndependenceScore,
        int independentSourceCount,
        int rawSourceCount,
        String temporalStatus,
        List<EvidenceDto> evidence,
        List<SourceDto> supportSources,
        List<SourceDto> counterSources,
        String rationale,
        Instant createdAt
) {
}
