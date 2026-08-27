package com.factchecker.claim;

import com.factchecker.domain.ClaimMode;
import com.factchecker.domain.TemporalStatus;
import com.factchecker.embedding.EmbeddingService;
import com.factchecker.embedding.VectorMath;
import com.factchecker.factcheck.SourceClassificationService;
import com.factchecker.factcheck.SourceEvaluation;
import com.factchecker.factcheck.VerdictCalculator;
import com.factchecker.rag.RagService;
import com.factchecker.rag.RetrievedChunk;
import com.factchecker.websearch.SearXngClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.factchecker.factcheck.SourceEvaluation.Stance;

/**
 * The single adversarial claim-verification pipeline behind claim decomposition, document audit,
 * and challenge mode alike (see class-level rationale in each caller). For every claim it:
 *  1. retrieves the document evidence (reusing RagService's retrieval half, no LLM),
 *  2. runs TWO web searches - one phrased to find support, one deliberately phrased to find
 *     counter-evidence - so a naive "confirm this" search bias doesn't hide disagreement,
 *  3. classifies every merged/deduped source's stance (reusing the same narrow LLM judgment used
 *     by the original simple fact-check flow),
 *  4. clusters sources into independent origins, classifies temporal status, and computes a
 *     verdict + confidence - all deterministically,
 *  5. builds a rationale from the real data, never a free-form LLM narrative.
 */
@Service
public class ClaimVerificationService {

    private static final int SUPPORT_SEARCH_RESULTS = 4;
    private static final int COUNTER_SEARCH_RESULTS_NORMAL = 4;
    private static final int COUNTER_SEARCH_RESULTS_CHALLENGE = 7;
    private static final String COUNTER_QUERY_SUFFIX = " debunked OR false OR criticism OR disproven OR limitations OR myth";

    private final RagService ragService;
    private final SearXngClient searXngClient;
    private final SourceClassificationService sourceClassificationService;
    private final SourceIndependenceService sourceIndependenceService;
    private final TemporalAnalysisService temporalAnalysisService;
    private final VerdictCalculator verdictCalculator;
    private final RationaleBuilder rationaleBuilder;
    private final EmbeddingService embeddingService;

    public ClaimVerificationService(RagService ragService, SearXngClient searXngClient,
                                     SourceClassificationService sourceClassificationService,
                                     SourceIndependenceService sourceIndependenceService,
                                     TemporalAnalysisService temporalAnalysisService,
                                     VerdictCalculator verdictCalculator, RationaleBuilder rationaleBuilder,
                                     EmbeddingService embeddingService) {
        this.ragService = ragService;
        this.searXngClient = searXngClient;
        this.sourceClassificationService = sourceClassificationService;
        this.sourceIndependenceService = sourceIndependenceService;
        this.temporalAnalysisService = temporalAnalysisService;
        this.verdictCalculator = verdictCalculator;
        this.rationaleBuilder = rationaleBuilder;
        this.embeddingService = embeddingService;
    }

    public VerifiedClaim verify(String documentId, AtomicClaim claim, ClaimMode mode) {
        List<RetrievedChunk> evidence = ragService.retrieveTopChunks(documentId, claim.claim());
        double retrievalConfidence = evidence.isEmpty() ? 0
                : VectorMath.toConfidencePercent(evidence.get(0).similarity());

        double fidelityConfidence = 0;
        if (!evidence.isEmpty()) {
            String context = ragService.buildContext(evidence);
            double[] claimEmbedding = embeddingService.embed(claim.claim());
            double[] contextEmbedding = embeddingService.embed(context);
            fidelityConfidence = VectorMath.toConfidencePercent(VectorMath.cosineSimilarity(claimEmbedding, contextEmbedding));
        }

        List<SourceEvaluation> merged = adversarialSearchAndClassify(claim.claim(), mode);

        List<SourceEvaluation> relevant = merged.stream().filter(s -> s.stance() != Stance.NOT_RELEVANT).toList();
        List<SourceEvaluation> supportSources = relevant.stream().filter(s -> s.stance() == Stance.SUPPORTS).toList();
        // MIXED sources "weaken or qualify" the claim per the adversarial-search spec, so they read
        // alongside outright contradictions rather than as unqualified support.
        List<SourceEvaluation> counterSources = relevant.stream()
                .filter(s -> s.stance() == Stance.CONTRADICTS || s.stance() == Stance.MIXED)
                .toList();

        SourceIndependenceService.IndependenceResult independence = sourceIndependenceService.evaluate(relevant);
        TemporalStatus temporalStatus = temporalAnalysisService.classify(claim.timeSensitive(), relevant);
        VerdictCalculator.VerdictOutcome outcome = verdictCalculator.compute(merged, independence.independentSourceCount());
        String rationale = rationaleBuilder.build(outcome.verdict(), relevant, independence, temporalStatus, mode);

        return new VerifiedClaim(
                claim.claim(), claim.type(), claim.timeSensitive(), mode,
                evidence, retrievalConfidence, fidelityConfidence,
                supportSources, counterSources,
                outcome.verdict(), outcome.webConfidence(),
                independence.independenceScore(), independence.independentSourceCount(), independence.rawSourceCount(),
                temporalStatus, rationale
        );
    }

    private List<SourceEvaluation> adversarialSearchAndClassify(String claimText, ClaimMode mode) {
        List<SearXngClient.SearXngResult> supportResults = searXngClient.search(claimText, SUPPORT_SEARCH_RESULTS);

        int counterCount = mode == ClaimMode.CHALLENGE ? COUNTER_SEARCH_RESULTS_CHALLENGE : COUNTER_SEARCH_RESULTS_NORMAL;
        List<SearXngClient.SearXngResult> counterResults = searXngClient.search(claimText + COUNTER_QUERY_SUFFIX, counterCount);

        Map<String, SearXngClient.SearXngResult> deduped = new LinkedHashMap<>();
        for (SearXngClient.SearXngResult r : supportResults) deduped.putIfAbsent(r.url(), r);
        for (SearXngClient.SearXngResult r : counterResults) deduped.putIfAbsent(r.url(), r);

        return sourceClassificationService.classifyBatch(claimText, new ArrayList<>(deduped.values()));
    }
}
