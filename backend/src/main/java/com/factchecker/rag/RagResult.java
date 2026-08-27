package com.factchecker.rag;

import java.util.List;

public record RagResult(
        String documentClaim,
        String explanation,
        boolean insufficientContext,
        double retrievalConfidence,
        double fidelityConfidence,
        List<RetrievedChunk> evidence
) {
}
