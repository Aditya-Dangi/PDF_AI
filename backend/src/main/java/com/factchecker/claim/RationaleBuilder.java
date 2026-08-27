package com.factchecker.claim;

import com.factchecker.domain.ClaimMode;
import com.factchecker.domain.TemporalStatus;
import com.factchecker.domain.Verdict;
import com.factchecker.factcheck.AuthorityTier;
import com.factchecker.factcheck.SourceEvaluation;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.factchecker.factcheck.SourceEvaluation.Stance;

/**
 * Assembles the "why this verdict" rationale entirely from real, already-computed data (never a
 * free-form LLM paragraph) - the spec asks for concise, evidence-based reasoning with no hidden
 * chain-of-thought, and a template built from the actual strongest sources, independence count,
 * and recency can't fabricate a source or a number the way an LLM summary could. It also costs zero
 * extra model calls.
 */
@Component
public class RationaleBuilder {

    private static final Map<AuthorityTier, Integer> TIER_RANK = Map.of(
            AuthorityTier.PRIMARY_AUTHORITY, 3,
            AuthorityTier.ESTABLISHED, 2,
            AuthorityTier.CONTEXT_ONLY, 1,
            AuthorityTier.UNKNOWN, 0
    );

    public String build(Verdict verdict, List<SourceEvaluation> relevantSources,
                         SourceIndependenceService.IndependenceResult independence,
                         TemporalStatus temporalStatus, ClaimMode mode) {
        StringBuilder sb = new StringBuilder();

        if (mode == ClaimMode.CHALLENGE) {
            sb.append("Challenge mode: counter-evidence search was prioritized. ");
        }

        Optional<SourceEvaluation> strongestSupport = strongest(relevantSources, Stance.SUPPORTS);
        Optional<SourceEvaluation> strongestCounter = strongest(relevantSources, Stance.CONTRADICTS);

        if (strongestSupport.isPresent()) {
            SourceEvaluation s = strongestSupport.get();
            sb.append("Strongest support: \"%s\" (%s) - %s ".formatted(s.title(), tierLabel(s.authorityTier()), s.snippet()));
        } else {
            sb.append("No source was found that clearly supports this claim. ");
        }

        if (strongestCounter.isPresent()) {
            SourceEvaluation s = strongestCounter.get();
            sb.append("Strongest contradiction: \"%s\" (%s) - %s ".formatted(s.title(), tierLabel(s.authorityTier()), s.snippet()));
        } else if (verdict != Verdict.INSUFFICIENT_EVIDENCE) {
            sb.append("No source was found that contradicts this claim. ");
        }

        if (independence.rawSourceCount() > 0) {
            sb.append("%d of %d relevant sources are independent origins (%.0f%% independence). "
                    .formatted(independence.independentSourceCount(), independence.rawSourceCount(),
                            independence.independenceScore() * 100));
        }

        sb.append(temporalNote(temporalStatus)).append(' ');

        String limitation = limitationNote(relevantSources, independence);
        if (limitation != null) sb.append(limitation);

        return sb.toString().trim();
    }

    private String temporalNote(TemporalStatus status) {
        return switch (status) {
            case CURRENT -> "Evidence recency: current sources are consistent with older ones.";
            case HISTORICAL_OUTDATED -> "Evidence recency: this claim was historically supported, but newer evidence points the other way - treat the current status as changed.";
            case TIME_SENSITIVE_UNVERIFIED -> "Evidence recency: this claim is time-sensitive and no recently dated evidence was found to confirm its current status.";
            case NOT_TIME_SENSITIVE -> "";
        };
    }

    private String limitationNote(List<SourceEvaluation> relevantSources,
                                   SourceIndependenceService.IndependenceResult independence) {
        if (relevantSources.isEmpty()) {
            return "Limitation: no relevant source was found for this claim at all.";
        }
        boolean anyHighAuthority = relevantSources.stream()
                .anyMatch(s -> s.authorityTier() == AuthorityTier.PRIMARY_AUTHORITY || s.authorityTier() == AuthorityTier.ESTABLISHED);
        if (!anyHighAuthority) {
            return "Limitation: no primary or established-authority source was found - all evidence is from lower-authority or unverified sources.";
        }
        if (independence.rawSourceCount() > 0 && independence.independenceScore() < 0.5) {
            return "Limitation: most sources trace back to a small number of origins, which limits how much they count as independent confirmation.";
        }
        boolean disagreement = relevantSources.stream().anyMatch(s -> s.stance() == Stance.SUPPORTS)
                && relevantSources.stream().anyMatch(s -> s.stance() == Stance.CONTRADICTS);
        if (disagreement) {
            return "Limitation: credible sources disagree on this claim - the verdict reflects the balance of evidence, not consensus.";
        }
        return null;
    }

    private Optional<SourceEvaluation> strongest(List<SourceEvaluation> sources, Stance stance) {
        return sources.stream()
                .filter(s -> s.stance() == stance)
                .max(Comparator.comparingInt(s -> TIER_RANK.getOrDefault(s.authorityTier(), 0)));
    }

    private String tierLabel(AuthorityTier tier) {
        return switch (tier) {
            case PRIMARY_AUTHORITY -> "primary authority";
            case ESTABLISHED -> "established";
            case CONTEXT_ONLY -> "context only";
            case UNKNOWN -> "unverified";
        };
    }
}
