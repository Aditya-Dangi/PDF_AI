package com.factchecker.factcheck;

import com.factchecker.domain.Verdict;

import java.util.List;

public record FactCheckResult(
        String claimText,
        String claimType,
        boolean checkable,
        Verdict verdict,
        double webConfidence,
        String summary,
        List<SourceEvaluation> sources
) {
}
