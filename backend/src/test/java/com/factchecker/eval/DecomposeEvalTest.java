package com.factchecker.eval;

import com.factchecker.claim.ClaimService;
import com.factchecker.config.AppProperties;
import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import com.factchecker.dto.ClaimDecomposeRequest;
import com.factchecker.dto.ClaimResponse;
import com.factchecker.repository.DocumentRepository;
import com.factchecker.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Flow C: ClaimService.decompose(document, userId, request), the real caller of
 * CLAIM_DECOMPOSITION_SYSTEM. This also incidentally re-exercises ClaimVerificationService (real
 * web search + classification) per extracted claim - a bonus signal, not what this flow targets,
 * per the approved design. The document itself carries no content: decomposition only depends on
 * the input statement text, not document chunks.
 */
@SpringBootTest
@Tag("eval")
@ExtendWith(EvalReportExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecomposeEvalTest {

    private static final String USER_ID = "eval-user-decompose";
    private static final int MAX_REASONABLE_CLAIM_COUNT = 8;

    @Autowired
    private ClaimService claimService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private RestClient.Builder restClientBuilder;
    @Autowired
    private ObjectMapper objectMapper;

    private EvalJudge judge;
    private Document document;

    private static final List<EvalCase> CASES = List.of(
            new EvalCase("c-regression-compound-claim", Flow.DECOMPOSE,
                    "Regular exercise reduces the risk of heart disease by 30% and is the safest way to maintain cardiovascular health.",
                    "Must split into at least two atomic claims: an effectiveness/magnitude claim (30% risk " +
                            "reduction) and a distinct \"safest way\" claim - neither merged into one nor dropped."),
            new EvalCase("c-robustness-single-simple-claim", Flow.DECOMPOSE,
                    "The Great Wall of China is over 13,000 miles long.",
                    "Already a single simple claim; must return exactly one atomic claim, faithfully preserving " +
                            "the length figure, without unnecessary fragmentation."),
            new EvalCase("c-robustness-opinion-excluded", Flow.DECOMPOSE,
                    "Exercise is the best thing you can do for your health, in my opinion.",
                    "The subjective \"best thing... in my opinion\" framing should be excluded as non-checkable; " +
                            "must not fabricate a checkable claim out of pure opinion."),
            new EvalCase("c-robustness-mixed-checkable-and-opinion", Flow.DECOMPOSE,
                    "The Amazon rainforest produces about 20% of the world's oxygen, and everyone should care more about protecting it.",
                    "Must extract the checkable factual claim (20% of oxygen) while excluding the \"everyone " +
                            "should care more\" recommendation as a checkable claim."),
            new EvalCase("c-robustness-three-part-compound", Flow.DECOMPOSE,
                    "The new policy will cut costs by 15%, improve customer satisfaction, and reduce employee turnover.",
                    "Should split into three distinct atomic claims (cost reduction, customer satisfaction, " +
                            "turnover reduction), each independently checkable, without inventing a fourth or merging any two."),
            new EvalCase("c-robustness-pronoun-resolution", Flow.DECOMPOSE,
                    "It was founded in 1975 and is now the largest company in its industry by revenue.",
                    "The subject (\"it\") is ambiguous with no context given; must not silently invent a specific " +
                            "fake company name to resolve the pronoun - vague/generic subject handling is acceptable, fabrication is not."),
            new EvalCase("c-robustness-no-checkable-content", Flow.DECOMPOSE,
                    "I really love how this project turned out; it was such a fun experience to build.",
                    "Should return no atomic claims, since this is purely a subjective statement with nothing independently fact-checkable."),
            new EvalCase("c-robustness-statistic-with-date", Flow.DECOMPOSE,
                    "The global population surpassed 8 billion people in November 2022.",
                    "Must faithfully preserve both the figure (8 billion) and the date (November 2022) in the " +
                            "resulting claim(s), without altering or dropping either."),
            new EvalCase("c-robustness-historical-fact-atomicity", Flow.DECOMPOSE,
                    "World War I began in 1914 and ended in 1918.",
                    "Should produce claim(s) faithfully capturing both the start year and end year - as one " +
                            "combined claim or two atomic ones - but no year should be dropped or altered."),
            new EvalCase("c-robustness-no-irrelevant-claims", Flow.DECOMPOSE,
                    "Our new database migration completed successfully last night, which the whole team is very proud of.",
                    "Must extract the factual claim (migration completed successfully) and must not include the " +
                            "subjective \"team is proud\" sentiment as a separate checkable claim.")
    );

    @BeforeAll
    void setUpJudge() {
        judge = new EvalJudge(appProperties, restClientBuilder, objectMapper);
    }

    @BeforeEach
    void createDocument() {
        Document doc = new Document();
        doc.setUserId(USER_ID);
        doc.setFilename("eval-decompose-fixture.pdf");
        doc.setStoragePath("/does/not/need/to/exist/for/this/test.pdf");
        doc.setPageCount(0);
        doc.setStatus(DocumentStatus.READY);
        document = documentRepository.save(doc);
    }

    @AfterEach
    void cleanUp() {
        try {
            documentService.delete(USER_ID, document.getId());
        } catch (Exception ignored) {
            // Best-effort - a case failure shouldn't also fail cleanup.
        }
    }

    @Test
    void runAllDecomposeCases() {
        for (EvalCase evalCase : CASES) {
            List<ClaimResponse> claims = claimService.decompose(document, USER_ID,
                    new ClaimDecomposeRequest(null, evalCase.input()));
            assertNotNull(claims, "ClaimService.decompose() must never return null");

            String actualResult = claims.isEmpty()
                    ? "(no atomic claims extracted)"
                    : claims.stream()
                            .map(c -> "- [%s] %s".formatted(c.claimType(), c.claimText()))
                            .collect(Collectors.joining("\n"));

            String deterministicFailure = checkDeterministic(claims);
            JudgeVerdict verdict = deterministicFailure != null
                    ? JudgeVerdict.fail(deterministicFailure, FailureCategory.DECOMPOSITION)
                    : judge.judge(evalCase, actualResult);

            EvalResultsCollector.record(new EvalCaseResult(evalCase, actualResult, verdict));
        }
    }

    private String checkDeterministic(List<ClaimResponse> claims) {
        if (claims.size() > MAX_REASONABLE_CLAIM_COUNT) {
            return "Decomposed into " + claims.size() + " claims from one statement - almost certainly excessive fragmentation.";
        }
        for (ClaimResponse claim : claims) {
            if (claim.claimText() == null || claim.claimText().isBlank()) {
                return "One of the extracted claims had blank text.";
            }
        }
        return null;
    }
}
