package com.factchecker.dto;

import java.util.List;
import java.util.Map;

public record AuditResponse(
        String status,
        int claimsDetected,
        int claimsInvestigated,
        double evidenceCoverage,
        Map<String, Integer> verdictCounts,
        String failureReason,
        List<ClaimResponse> claims
) {
}
