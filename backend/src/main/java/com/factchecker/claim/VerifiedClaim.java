package com.factchecker.claim;

import com.factchecker.domain.ClaimMode;
import com.factchecker.domain.TemporalStatus;
import com.factchecker.domain.Verdict;
import com.factchecker.factcheck.SourceEvaluation;
import com.factchecker.rag.RetrievedChunk;

import java.util.List;

public record VerifiedClaim(
        String claimText,
        String claimType,
        boolean timeSensitive,
        ClaimMode mode,
        List<RetrievedChunk> evidence,
        double retrievalConfidence,
        double fidelityConfidence,
        List<SourceEvaluation> supportSources,
        List<SourceEvaluation> counterSources,
        Verdict verdict,
        double webConfidence,
        double sourceIndependenceScore,
        int independentSourceCount,
        int rawSourceCount,
        TemporalStatus temporalStatus,
        String rationale
) {
}
