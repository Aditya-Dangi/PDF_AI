package com.factchecker.dto;

import java.util.List;

public record AnswerResponse(
        String messageId,
        String question,
        String documentClaim,
        String explanation,
        boolean insufficientContext,
        double retrievalConfidence,
        double fidelityConfidence,
        List<EvidenceDto> evidence,
        Long durationMs
) {
}
