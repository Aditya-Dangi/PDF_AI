package com.factchecker.claim;

import com.factchecker.factcheck.SourceEvaluation;
import com.factchecker.factcheck.SourceQualityService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Distinguishes "number of sources" from "number of independent evidence origins": several
 * articles on different URLs that all trace back to the same domain (e.g. five pages on the same
 * outlet, or a wire story republished under one masthead) are one origin, not several confirmations.
 * Clustering is by registrable domain - deterministic and free, no LLM call. This deliberately
 * doesn't try to detect syndication across DIFFERENT domains (e.g. the same wire story picked up by
 * two outlets) - that would need content-similarity clustering, a reasonable v2 extension, but
 * domain-based clustering already prevents the most common false-confidence case: one site's
 * multiple pages all counting as separate "sources".
 */
@Service
public class SourceIndependenceService {

    private final SourceQualityService sourceQualityService;

    public SourceIndependenceService(SourceQualityService sourceQualityService) {
        this.sourceQualityService = sourceQualityService;
    }

    public record IndependenceResult(int rawSourceCount, int independentSourceCount, double independenceScore) {
    }

    public IndependenceResult evaluate(List<SourceEvaluation> relevantSources) {
        int raw = relevantSources.size();
        if (raw == 0) return new IndependenceResult(0, 0, 1.0);

        Set<String> origins = relevantSources.stream()
                .map(s -> {
                    String domain = sourceQualityService.extractDomain(s.url());
                    return domain != null ? domain : s.url();
                })
                .collect(Collectors.toSet());

        int independent = origins.size();
        double score = Math.round((independent / (double) raw) * 10000.0) / 10000.0;

        return new IndependenceResult(raw, independent, score);
    }
}
