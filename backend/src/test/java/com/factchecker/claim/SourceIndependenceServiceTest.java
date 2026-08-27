package com.factchecker.claim;

import com.factchecker.factcheck.AuthorityTier;
import com.factchecker.factcheck.SourceEvaluation;
import com.factchecker.factcheck.SourceQualityService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.factchecker.factcheck.SourceEvaluation.Stance;
import static org.assertj.core.api.Assertions.assertThat;

class SourceIndependenceServiceTest {

    private final SourceIndependenceService service = new SourceIndependenceService(new SourceQualityService());

    @Test
    void noSourcesYieldsFullIndependenceByConvention() {
        SourceIndependenceService.IndependenceResult result = service.evaluate(List.of());

        assertThat(result.rawSourceCount()).isZero();
        assertThat(result.independentSourceCount()).isZero();
        assertThat(result.independenceScore()).isEqualTo(1.0);
    }

    @Test
    void sourcesOnDifferentDomainsAreFullyIndependent() {
        List<SourceEvaluation> sources = List.of(
                source("https://reuters.com/a"),
                source("https://bbc.com/b"),
                source("https://who.int/c")
        );

        SourceIndependenceService.IndependenceResult result = service.evaluate(sources);

        assertThat(result.rawSourceCount()).isEqualTo(3);
        assertThat(result.independentSourceCount()).isEqualTo(3);
        assertThat(result.independenceScore()).isEqualTo(1.0);
    }

    @Test
    void multiplePagesOnTheSameDomainCountAsOneOrigin() {
        List<SourceEvaluation> sources = List.of(
                source("https://example.com/article-1"),
                source("https://example.com/article-2"),
                source("https://www.example.com/article-3"), // www. is stripped, still same origin
                source("https://reuters.com/other-story")
        );

        SourceIndependenceService.IndependenceResult result = service.evaluate(sources);

        assertThat(result.rawSourceCount()).isEqualTo(4);
        assertThat(result.independentSourceCount()).isEqualTo(2); // example.com + reuters.com
        assertThat(result.independenceScore()).isEqualTo(0.5);
    }

    private SourceEvaluation source(String url) {
        return new SourceEvaluation(url, "Title", "snippet", Stance.SUPPORTS, AuthorityTier.UNKNOWN, null);
    }
}
