package com.factchecker.factcheck;

import com.factchecker.domain.Verdict;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.factchecker.factcheck.SourceEvaluation.Stance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * VerdictCalculator is the only place in the app that produces a fact-check verdict and a
 * confidence number, and it does so with a fixed formula (see the class javadoc) rather than
 * asking the LLM to self-report. These tests pin that formula down so a future change to the
 * weights/thresholds is a deliberate, visible decision rather than an accidental regression.
 */
class VerdictCalculatorTest {

    private final VerdictCalculator calculator = new VerdictCalculator();

    @Test
    void noSourcesAtAllYieldsInsufficientEvidence() {
        VerdictCalculator.VerdictOutcome outcome = calculator.compute(List.of());

        assertThat(outcome.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.webConfidence()).isZero();
    }

    @Test
    void onlyNotRelevantSourcesYieldsInsufficientEvidence() {
        List<SourceEvaluation> sources = List.of(
                source(Stance.NOT_RELEVANT, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.NOT_RELEVANT, AuthorityTier.ESTABLISHED, null)
        );

        VerdictCalculator.VerdictOutcome outcome = calculator.compute(sources);

        assertThat(outcome.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.webConfidence()).isZero();
    }

    @Test
    void unanimousHighAuthoritySupportIsStronglySupported() {
        List<SourceEvaluation> sources = List.of(
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null)
        );

        VerdictCalculator.VerdictOutcome outcome = calculator.compute(sources);

        assertThat(outcome.verdict()).isEqualTo(Verdict.SUPPORTED);
        // 0.35*sourceCount(1.0) + 0.30*authority(1.0) + 0.25*agreement(1.0) + 0.10*recency(0.5, no dates) = 0.95
        assertThat(outcome.webConfidence()).isCloseTo(95.0, within(0.01));
    }

    @Test
    void unanimousLowAuthorityContradictionIsContradicted() {
        List<SourceEvaluation> sources = List.of(
                source(Stance.CONTRADICTS, AuthorityTier.UNKNOWN, null),
                source(Stance.CONTRADICTS, AuthorityTier.UNKNOWN, null)
        );

        VerdictCalculator.VerdictOutcome outcome = calculator.compute(sources);

        assertThat(outcome.verdict()).isEqualTo(Verdict.CONTRADICTED);
        // 0.35*sourceCount(0.5) + 0.30*authority(0.3) + 0.25*agreement(1.0) + 0.10*recency(0.5) = 0.565
        assertThat(outcome.webConfidence()).isCloseTo(56.5, within(0.01));
    }

    @Test
    void mixedDominantEvidenceIsMisleading() {
        List<SourceEvaluation> sources = List.of(
                source(Stance.MIXED, AuthorityTier.ESTABLISHED, null),
                source(Stance.MIXED, AuthorityTier.ESTABLISHED, null),
                source(Stance.MIXED, AuthorityTier.ESTABLISHED, null),
                source(Stance.SUPPORTS, AuthorityTier.ESTABLISHED, null)
        );

        VerdictCalculator.VerdictOutcome outcome = calculator.compute(sources);

        assertThat(outcome.verdict()).isEqualTo(Verdict.MISLEADING);
        assertThat(outcome.webConfidence()).isCloseTo(79.75, within(0.01));
    }

    @Test
    void moderateSupportLeaningEvidenceIsPartiallySupported() {
        List<SourceEvaluation> sources = List.of(
                source(Stance.SUPPORTS, AuthorityTier.UNKNOWN, null),
                source(Stance.SUPPORTS, AuthorityTier.UNKNOWN, null),
                source(Stance.CONTRADICTS, AuthorityTier.UNKNOWN, null),
                source(Stance.MIXED, AuthorityTier.UNKNOWN, null)
        );

        VerdictCalculator.VerdictOutcome outcome = calculator.compute(sources);

        assertThat(outcome.verdict()).isEqualTo(Verdict.PARTIALLY_SUPPORTED);
        assertThat(outcome.webConfidence()).isCloseTo(61.5, within(0.01));
    }

    @Test
    void moderateContradictLeaningEvidenceIsUnsupported() {
        List<SourceEvaluation> sources = List.of(
                source(Stance.CONTRADICTS, AuthorityTier.UNKNOWN, null),
                source(Stance.CONTRADICTS, AuthorityTier.UNKNOWN, null),
                source(Stance.SUPPORTS, AuthorityTier.UNKNOWN, null),
                source(Stance.MIXED, AuthorityTier.UNKNOWN, null)
        );

        VerdictCalculator.VerdictOutcome outcome = calculator.compute(sources);

        assertThat(outcome.verdict()).isEqualTo(Verdict.UNSUPPORTED);
        assertThat(outcome.webConfidence()).isCloseTo(61.5, within(0.01));
    }

    @Test
    void notRelevantSourcesAreExcludedFromBothVerdictAndSourceCount() {
        List<SourceEvaluation> withNotRelevant = List.of(
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.NOT_RELEVANT, AuthorityTier.PRIMARY_AUTHORITY, null)
        );
        List<SourceEvaluation> withoutNotRelevant = List.of(
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null)
        );

        VerdictCalculator.VerdictOutcome withNotRelevantOutcome = calculator.compute(withNotRelevant);
        VerdictCalculator.VerdictOutcome withoutNotRelevantOutcome = calculator.compute(withoutNotRelevant);

        // The extra NOT_RELEVANT source must not change the source-count factor - confidence is identical either way.
        assertThat(withNotRelevantOutcome.webConfidence()).isEqualTo(withoutNotRelevantOutcome.webConfidence());
        assertThat(withNotRelevantOutcome.verdict()).isEqualTo(Verdict.SUPPORTED);
    }

    @Test
    void recentSourceYieldsHigherConfidenceThanOldSource() {
        String recentDate = LocalDate.now().minusYears(1).toString();
        String oldDate = LocalDate.now().minusYears(10).toString();

        VerdictCalculator.VerdictOutcome recentOutcome = calculator.compute(
                List.of(source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, recentDate)));
        VerdictCalculator.VerdictOutcome oldOutcome = calculator.compute(
                List.of(source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, oldDate)));

        assertThat(recentOutcome.webConfidence()).isCloseTo(73.75, within(0.01));
        assertThat(oldOutcome.webConfidence()).isCloseTo(68.75, within(0.01));
        assertThat(recentOutcome.webConfidence()).isGreaterThan(oldOutcome.webConfidence());
    }

    @Test
    void unparseableDateIsTreatedLikeAMissingDateRatherThanCrashing() {
        VerdictCalculator.VerdictOutcome garbageDateOutcome = calculator.compute(
                List.of(source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, "not-a-real-date")));
        VerdictCalculator.VerdictOutcome noDateOutcome = calculator.compute(
                List.of(source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null)));

        assertThat(garbageDateOutcome.webConfidence()).isEqualTo(noDateOutcome.webConfidence());
    }

    @Test
    void independentSourceCountOverloadUsesTheProvidedCountInsteadOfRawRelevantSize() {
        // Four SUPPORTS sources that all cluster into a single independent origin should score like
        // a single well-supported source (low source-count factor), not like four confirmations.
        List<SourceEvaluation> sources = List.of(
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null),
                source(Stance.SUPPORTS, AuthorityTier.PRIMARY_AUTHORITY, null)
        );

        VerdictCalculator.VerdictOutcome fullCredit = calculator.compute(sources);
        VerdictCalculator.VerdictOutcome oneIndependentOrigin = calculator.compute(sources, 1);

        assertThat(fullCredit.webConfidence()).isCloseTo(95.0, within(0.01));
        // sourceCountFactor drops from 1.0 (4/4) to 0.25 (1/4): 0.35*(1.0-0.25) = 0.2625 lower raw score.
        assertThat(oneIndependentOrigin.webConfidence()).isCloseTo(95.0 - 26.25, within(0.01));
        assertThat(oneIndependentOrigin.verdict()).isEqualTo(fullCredit.verdict()); // the verdict itself is unaffected, only confidence
    }

    private SourceEvaluation source(Stance stance, AuthorityTier tier, String publishedDate) {
        return new SourceEvaluation("https://example.com", "Example", "snippet", stance, tier, publishedDate);
    }
}
