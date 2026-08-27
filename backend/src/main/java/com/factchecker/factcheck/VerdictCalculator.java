package com.factchecker.factcheck;

import com.factchecker.domain.Verdict;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Map;

import static com.factchecker.factcheck.SourceEvaluation.Stance;

/**
 * Turns a list of per-source stance classifications into a verdict and a Web Verification
 * Confidence score, entirely deterministically. The LLM only classifies each individual source's
 * stance toward the claim (a narrow, checkable judgment); it is never asked "how confident are you"
 * or "is this true" - that would let it manufacture certainty. This calculator is the only place
 * confidence numbers are produced, from source count, authority, agreement, and recency.
 */
@Component
public class VerdictCalculator {

    private static final Map<AuthorityTier, Double> TIER_WEIGHT = Map.of(
            AuthorityTier.PRIMARY_AUTHORITY, 1.0,
            AuthorityTier.ESTABLISHED, 0.7,
            AuthorityTier.CONTEXT_ONLY, 0.4,
            AuthorityTier.UNKNOWN, 0.3
    );

    public record VerdictOutcome(Verdict verdict, double webConfidence) {
    }

    public VerdictOutcome compute(List<SourceEvaluation> sources) {
        List<SourceEvaluation> relevant = relevantOf(sources);
        return relevant.isEmpty()
                ? new VerdictOutcome(Verdict.INSUFFICIENT_EVIDENCE, 0)
                : computeFromRelevant(relevant, relevant.size());
    }

    /**
     * Same computation, but the source-count factor is driven by the number of INDEPENDENT
     * evidence origins (see SourceIndependenceService) rather than the raw relevant-source count -
     * five pages from one outlet must not buy the same confidence boost as five independent ones.
     */
    public VerdictOutcome compute(List<SourceEvaluation> sources, int independentSourceCount) {
        List<SourceEvaluation> relevant = relevantOf(sources);
        return relevant.isEmpty()
                ? new VerdictOutcome(Verdict.INSUFFICIENT_EVIDENCE, 0)
                : computeFromRelevant(relevant, independentSourceCount);
    }

    private List<SourceEvaluation> relevantOf(List<SourceEvaluation> sources) {
        return sources.stream().filter(s -> s.stance() != Stance.NOT_RELEVANT).toList();
    }

    private VerdictOutcome computeFromRelevant(List<SourceEvaluation> relevant, int sourceCountBasis) {
        double supportScore = weightedSum(relevant, Stance.SUPPORTS);
        double contradictScore = weightedSum(relevant, Stance.CONTRADICTS);
        double mixedScore = weightedSum(relevant, Stance.MIXED);
        double total = supportScore + contradictScore + mixedScore;

        double supportRatio = total == 0 ? 0 : supportScore / total;
        double contradictRatio = total == 0 ? 0 : contradictScore / total;
        double mixedRatio = total == 0 ? 0 : mixedScore / total;

        Verdict verdict = decideVerdict(supportRatio, contradictRatio, mixedRatio);
        double confidence = computeConfidence(relevant, sourceCountBasis, supportRatio, contradictRatio, mixedRatio);

        return new VerdictOutcome(verdict, confidence);
    }

    private Verdict decideVerdict(double supportRatio, double contradictRatio, double mixedRatio) {
        if (contradictRatio >= 0.65) return Verdict.CONTRADICTED;
        if (supportRatio >= 0.65) return Verdict.SUPPORTED;
        if (mixedRatio >= 0.4) return Verdict.MISLEADING;
        if (supportRatio > contradictRatio && supportRatio >= 0.35) return Verdict.PARTIALLY_SUPPORTED;
        if (contradictRatio > supportRatio && contradictRatio >= 0.35) return Verdict.UNSUPPORTED;
        return Verdict.MISLEADING;
    }

    private double computeConfidence(List<SourceEvaluation> relevant, int sourceCountBasis, double supportRatio,
                                      double contradictRatio, double mixedRatio) {
        double sourceCountFactor = Math.min(1.0, sourceCountBasis / 4.0);

        double authorityFactor = relevant.stream()
                .mapToDouble(s -> TIER_WEIGHT.getOrDefault(s.authorityTier(), 0.3))
                .average()
                .orElse(0.3);

        double agreementFactor = Math.max(supportRatio, Math.max(contradictRatio, mixedRatio));

        double recencyFactor = recencyFactor(relevant);

        double raw = 0.35 * sourceCountFactor + 0.30 * authorityFactor + 0.25 * agreementFactor + 0.10 * recencyFactor;
        return Math.round(Math.max(0, Math.min(1, raw)) * 10000.0) / 100.0;
    }

    private double recencyFactor(List<SourceEvaluation> relevant) {
        int currentYear = Year.now().getValue();
        long withDate = relevant.stream().filter(s -> s.publishedDate() != null && !s.publishedDate().isBlank()).count();
        if (withDate == 0) return 0.5;

        long recent = relevant.stream()
                .map(SourceEvaluation::publishedDate)
                .filter(d -> d != null && !d.isBlank())
                .filter(d -> isRecent(d, currentYear))
                .count();

        return Math.min(1.0, 0.5 + (0.5 * recent / withDate));
    }

    private boolean isRecent(String dateStr, int currentYear) {
        try {
            LocalDate date = LocalDate.parse(dateStr.length() > 10 ? dateStr.substring(0, 10) : dateStr);
            return (currentYear - date.getYear()) <= 3;
        } catch (Exception ex) {
            return false;
        }
    }

    private double weightedSum(List<SourceEvaluation> sources, Stance stance) {
        return sources.stream()
                .filter(s -> s.stance() == stance)
                .mapToDouble(s -> TIER_WEIGHT.getOrDefault(s.authorityTier(), 0.3))
                .sum();
    }
}
