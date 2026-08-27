package com.factchecker.claim;

import com.factchecker.domain.TemporalStatus;
import com.factchecker.factcheck.AuthorityTier;
import com.factchecker.factcheck.SourceEvaluation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.factchecker.factcheck.SourceEvaluation.Stance;
import static org.assertj.core.api.Assertions.assertThat;

class TemporalAnalysisServiceTest {

    private final TemporalAnalysisService service = new TemporalAnalysisService();

    @Test
    void nonTimeSensitiveClaimsAreAlwaysNotTimeSensitiveRegardlessOfSources() {
        assertThat(service.classify(false, List.of())).isEqualTo(TemporalStatus.NOT_TIME_SENSITIVE);
    }

    @Test
    void timeSensitiveClaimWithNoDatedSourcesIsUnverified() {
        List<SourceEvaluation> sources = List.of(source(Stance.SUPPORTS, null));

        assertThat(service.classify(true, sources)).isEqualTo(TemporalStatus.TIME_SENSITIVE_UNVERIFIED);
    }

    @Test
    void timeSensitiveClaimWithOnlyOldSourcesIsUnverified() {
        String oldDate = LocalDate.now().minusYears(10).toString();
        List<SourceEvaluation> sources = List.of(source(Stance.SUPPORTS, oldDate));

        assertThat(service.classify(true, sources)).isEqualTo(TemporalStatus.TIME_SENSITIVE_UNVERIFIED);
    }

    @Test
    void timeSensitiveClaimWithConsistentRecentSourcesIsCurrent() {
        String recent1 = LocalDate.now().minusMonths(6).toString();
        String recent2 = LocalDate.now().minusMonths(1).toString();
        List<SourceEvaluation> sources = List.of(
                source(Stance.SUPPORTS, recent1),
                source(Stance.SUPPORTS, recent2)
        );

        assertThat(service.classify(true, sources)).isEqualTo(TemporalStatus.CURRENT);
    }

    @Test
    void newerEvidenceContradictingOlderEvidenceIsHistoricalOutdated() {
        String old = LocalDate.now().minusYears(5).toString();
        String recent = LocalDate.now().minusMonths(2).toString();
        List<SourceEvaluation> sources = List.of(
                source(Stance.SUPPORTS, old),
                source(Stance.CONTRADICTS, recent)
        );

        assertThat(service.classify(true, sources)).isEqualTo(TemporalStatus.HISTORICAL_OUTDATED);
    }

    private SourceEvaluation source(Stance stance, String publishedDate) {
        return new SourceEvaluation("https://example.com", "Title", "snippet", stance, AuthorityTier.ESTABLISHED, publishedDate);
    }
}
