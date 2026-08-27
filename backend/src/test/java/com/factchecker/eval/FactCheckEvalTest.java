package com.factchecker.eval;

import com.factchecker.config.AppProperties;
import com.factchecker.factcheck.FactCheckResult;
import com.factchecker.factcheck.FactCheckService;
import com.factchecker.factcheck.SourceEvaluation;
import com.factchecker.repository.WebCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Flow B: FactCheckService.factCheck(text), the real caller of CLAIM_EXTRACTION_SYSTEM,
 * SOURCE_BATCH_CLASSIFICATION_SYSTEM, and FACT_CHECK_SUMMARY_SYSTEM together - includes a real
 * SearXNG web search, so every claim here is a stable, uncontroversial fact (never time-sensitive).
 */
@SpringBootTest
@Tag("eval")
@ExtendWith(EvalReportExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactCheckEvalTest {

    @Autowired
    private FactCheckService factCheckService;
    @Autowired
    private WebCacheRepository webCacheRepository;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private RestClient.Builder restClientBuilder;
    @Autowired
    private ObjectMapper objectMapper;

    private EvalJudge judge;

    private static final List<EvalCase> CASES = List.of(
            new EvalCase("b-regression-stable-fact-eiffel", Flow.FACT_CHECK,
                    "The Eiffel Tower is located in Paris, France.",
                    "Claim extraction must faithfully preserve this statement; verdict should be SUPPORTED " +
                            "given multiple reputable sources; the summary must not add unsupported extra conclusions."),
            new EvalCase("b-robustness-stable-fact-boiling-point", Flow.FACT_CHECK,
                    "Water boils at 100 degrees Celsius at sea level atmospheric pressure.",
                    "Verdict should be SUPPORTED; the summary must not introduce caveats or conclusions the sources don't state."),
            new EvalCase("b-robustness-false-claim", Flow.FACT_CHECK,
                    "The sun revolves around the Earth.",
                    "Should be classified as a checkable factual/scientific claim, and the verdict should reflect " +
                            "that sources contradict it, not treat it as SUPPORTED."),
            new EvalCase("b-robustness-opinion-not-checkable", Flow.FACT_CHECK,
                    "Chocolate ice cream is the best flavor.",
                    "Claim extraction must classify this as not independently checkable (an opinion); the flow " +
                            "must not perform web verification as if it were a factual claim."),
            new EvalCase("b-robustness-recommendation-not-checkable", Flow.FACT_CHECK,
                    "You should always back up your database before a migration.",
                    "Must be classified as a recommendation, not a checkable factual claim."),
            new EvalCase("b-robustness-compound-extraction", Flow.FACT_CHECK,
                    "Mount Everest is the tallest mountain above sea level and is located in the Himalayas.",
                    "Claim extraction should produce a faithful, self-contained claim capturing the core " +
                            "assertion without dropping either the height or location fact."),
            new EvalCase("b-robustness-stable-historical-fact", Flow.FACT_CHECK,
                    "World War II ended in 1945.",
                    "Verdict should be SUPPORTED; the summary must accurately reflect source agreement without " +
                            "introducing unsupported nuance."),
            new EvalCase("b-robustness-geography-fact", Flow.FACT_CHECK,
                    "Australia is both a country and a continent.",
                    "Claim extraction must preserve both parts of the compound assertion; verdict should be SUPPORTED."),
            new EvalCase("b-robustness-source-classification-nuance", Flow.FACT_CHECK,
                    "Coffee consumption is linked to a lower risk of type 2 diabetes in observational studies.",
                    "Source classification should recognize this as a correlational claim; the verdict/summary " +
                            "must not overstate it as proven causation."),
            new EvalCase("b-robustness-basic-math-fact", Flow.FACT_CHECK,
                    "There are seven days in a week.",
                    "Verdict should be SUPPORTED - this is a trivially well-established fact."),
            new EvalCase("b-robustness-summary-fidelity", Flow.FACT_CHECK,
                    "Photosynthesis is the process by which plants convert sunlight into chemical energy.",
                    "The summary must accurately reflect what supporting sources say, without inventing " +
                            "mechanistic details the sources don't include."),
            new EvalCase("b-robustness-prediction-not-checkable", Flow.FACT_CHECK,
                    "AI will replace most software engineering jobs within the next two years.",
                    "Must be classified as a prediction, not an independently checkable factual claim.")
    );

    @BeforeAll
    void setUpJudge() {
        judge = new EvalJudge(appProperties, restClientBuilder, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        // Isolated in-memory test database (see src/test/resources/application.yml) - cleared anyway
        // at JVM exit, but cleared explicitly here too so no cache entries linger across test runs
        // within the same suite execution.
        webCacheRepository.deleteAll();
    }

    @Test
    void runAllFactCheckCases() {
        for (EvalCase evalCase : CASES) {
            FactCheckResult result = factCheckService.factCheck(evalCase.input());
            assertNotNull(result, "FactCheckService.factCheck() must never return null");

            String actualResult = """
                    claim: %s
                    claimType: %s
                    checkable: %s
                    verdict: %s
                    webConfidence: %s
                    summary: %s
                    sources: %s
                    """.formatted(
                    result.claimText(), result.claimType(), result.checkable(),
                    result.verdict(), result.webConfidence(), result.summary(),
                    formatSources(result.sources())
            );

            String deterministicFailure = checkDeterministic(result);
            JudgeVerdict verdict = deterministicFailure != null
                    ? JudgeVerdict.fail(deterministicFailure, FailureCategory.EXTRACTION)
                    : judge.judge(evalCase, actualResult);

            EvalResultsCollector.record(new EvalCaseResult(evalCase, actualResult, verdict));
        }
    }

    private String formatSources(List<SourceEvaluation> sources) {
        if (sources == null || sources.isEmpty()) return "(none)";
        return sources.stream()
                .map(s -> "[%s] %s".formatted(s.stance(), s.title()))
                .collect(Collectors.joining("; "));
    }

    private String checkDeterministic(FactCheckResult result) {
        if (result.claimText() == null || result.claimText().isBlank()) {
            return "claimText was null or blank.";
        }
        if (result.summary() == null || result.summary().isBlank()) {
            return "summary was null or blank.";
        }
        if (result.verdict() == null) {
            return "verdict was null.";
        }
        return null;
    }
}
