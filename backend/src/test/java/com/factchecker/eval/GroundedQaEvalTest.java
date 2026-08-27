package com.factchecker.eval;

import com.factchecker.config.AppProperties;
import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import com.factchecker.rag.RagResult;
import com.factchecker.rag.RagService;
import com.factchecker.repository.DocumentRepository;
import com.factchecker.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.factchecker.eval.SyntheticPdfBuilder.line;
import static com.factchecker.eval.SyntheticPdfBuilder.section;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Flow A: real document upload -> real async processing (extract/chunk/embed) -> RagService.answer(),
 * the real GROUNDED_QA_SYSTEM caller. Excluded from normal `mvn test` via the "eval" tag - run
 * explicitly, see backend/README or the eval report for instructions.
 */
@SpringBootTest
@Tag("eval")
@ExtendWith(EvalReportExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroundedQaEvalTest {

    private static final String USER_ID = "eval-user-grounded-qa";
    private static final int MAX_DOCUMENT_CLAIM_CHARS = 400;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private RagService ragService;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private RestClient.Builder restClientBuilder;
    @Autowired
    private ObjectMapper objectMapper;

    private EvalJudge judge;
    private final List<String> documentIdsToCleanUp = new ArrayList<>();

    @BeforeAll
    void setUpJudge() {
        judge = new EvalJudge(appProperties, restClientBuilder, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        for (String documentId : documentIdsToCleanUp) {
            try {
                documentService.delete(USER_ID, documentId);
            } catch (Exception ignored) {
                // Best-effort - a case failure shouldn't also fail cleanup of an already-broken fixture.
            }
        }
        documentIdsToCleanUp.clear();
    }

    @Test
    void technicalReferenceDocument() throws Exception {
        byte[] pdf = SyntheticPdfBuilder.build(List.of(
                List.of( // page 1: table of contents
                        section("TABLE OF CONTENTS"),
                        line("1. Introduction ....................................... 2"),
                        line("2. Connection Pooling ................................. 3"),
                        line("3. Caching Strategies ................................. 4"),
                        line("4. My Project Notes ................................... 5")
                ),
                List.of( // page 2: generic intro
                        section("1. INTRODUCTION"),
                        line("This reference manual documents core backend infrastructure concepts used across our services."),
                        line("Read each section independently; sections do not depend on each other.")
                ),
                List.of( // page 3: heading glued directly to code, then a real explanatory paragraph
                        section("2. CONNECTION POOLING"),
                        line("Connection Pooling:"),
                        line("public Connection getConnection() { return pool.borrow(); }"),
                        section("Pooling avoids the overhead of establishing a new TCP connection and authentication"),
                        line("handshake for every database query, which can dominate latency under high request volume.")
                ),
                List.of( // page 4: unrelated topic - an irrelevant retrieval candidate for pooling questions
                        section("3. CACHING STRATEGIES"),
                        line("Caching stores frequently accessed data in a fast-access layer to avoid repeated"),
                        line("expensive computation or I/O. Common eviction policies include LRU, LFU, and TTL.")
                ),
                List.of( // page 5: first-person project-specific usage
                        section("4. MY PROJECT NOTES"),
                        line("In my project, I implemented connection pooling using HikariCP with a maximum pool"),
                        line("size of 10 connections, because load testing showed connection exhaustion once"),
                        line("concurrent users exceeded 50.")
                )
        ));

        String documentId = uploadAndAwaitReady(pdf, "technical-reference.pdf");

        runCase(new EvalCase("a-regression-toc-pollution", Flow.GROUNDED_QA,
                "What is connection pooling in this document?",
                "Must ground the answer in the real Connection Pooling section, not the table-of-contents " +
                        "entry; documentClaim must not contain dot-leader characters (....) or a raw page-index line."),
                documentId);

        runCase(new EvalCase("a-regression-runon-documentclaim", Flow.GROUNDED_QA,
                "What is connection pooling?",
                "documentClaim must be a clean, well-formed sentence - not a raw run-on quote gluing the " +
                        "\"Connection Pooling:\" heading label directly to the code snippet with no separating punctuation."),
                documentId);

        runCase(new EvalCase("a-regression-ignores-in-my-project", Flow.GROUNDED_QA,
                "What connection pooling approach have I used in my project?",
                "Must center on the first-person project-specific passage (HikariCP, max pool size 10, " +
                        "exhaustion at 50 concurrent users), not fall back to a generic definition of connection pooling."),
                documentId);

        runCase(new EvalCase("a-robustness-irrelevant-candidate", Flow.GROUNDED_QA,
                "What caching eviction policies are mentioned, and are they related to connection pooling?",
                "Must correctly identify LRU/LFU/TTL from the Caching Strategies section and must NOT " +
                        "conflate caching with connection pooling as if they were the same mechanism."),
                documentId);

        runCase(new EvalCase("a-robustness-multi-fact-comparison", Flow.GROUNDED_QA,
                "According to this document, what are two different techniques for improving performance, and how do they differ?",
                "Must identify connection pooling and caching as the two techniques and accurately describe " +
                        "how they differ (reusing connections vs. storing computed/fetched data)."),
                documentId);

        runCase(new EvalCase("a-robustness-explicit-comparison", Flow.GROUNDED_QA,
                "Is caching the same thing as connection pooling?",
                "Must clearly say no and explain the distinction, not treat them as interchangeable."),
                documentId);

        runCase(new EvalCase("a-robustness-precise-numeric-detail", Flow.GROUNDED_QA,
                "What is the maximum pool size I used in my project, and why did I choose it?",
                "Must state 10 as the max pool size and cite load testing showing exhaustion at 50 concurrent " +
                        "users as the reason - not a different number or a vague/generic justification."),
                documentId);
    }

    @Test
    void deploymentAndDataManual() throws Exception {
        byte[] pdf = SyntheticPdfBuilder.build(List.of(
                List.of(section("DATABASE"), line("The production system uses PostgreSQL as its primary database.")),
                List.of(
                        section("HISTORY"),
                        line("Before the 2023 migration, the legacy system used MySQL for its primary datastore."),
                        line("The migration to PostgreSQL was completed in March 2023 for better JSON support.")
                ),
                List.of(
                        section("DEPLOYMENT"),
                        line("Deployments are triggered via a GitHub Actions workflow on merge to main."),
                        line("The workflow builds a Docker image and pushes it to the internal registry.")
                ),
                List.of(section("TEAM"), line("The backend team consists of three engineers rotating on-call responsibilities weekly."))
        ));

        String documentId = uploadAndAwaitReady(pdf, "deployment-and-data-manual.pdf");

        runCase(new EvalCase("a-robustness-conflicting-evidence", Flow.GROUNDED_QA,
                "What database does the system use?",
                "Must identify PostgreSQL as the current/primary database, treating the MySQL mention as " +
                        "historical/legacy context rather than presenting the two as contradictory or being confused about which is current."),
                documentId);

        runCase(new EvalCase("a-robustness-missing-information", Flow.GROUNDED_QA,
                "What monitoring or alerting tool is used in production?",
                "The document does not mention any monitoring/alerting tool; must indicate the document " +
                        "doesn't address this (insufficientContext) rather than inventing an answer."),
                documentId);

        runCase(new EvalCase("a-robustness-irrelevant-section-nearby", Flow.GROUNDED_QA,
                "How is a new version deployed to production?",
                "Must ground the answer in the DEPLOYMENT section (GitHub Actions, Docker image, registry) " +
                        "and not be distracted by the unrelated TEAM section."),
                documentId);

        runCase(new EvalCase("a-robustness-plain-factual-lookup", Flow.GROUNDED_QA,
                "How often does on-call rotate?",
                "Must correctly extract \"weekly\" from the TEAM section."),
                documentId);
    }

    @Test
    void frontendNotesDocument() throws Exception {
        byte[] pdf = SyntheticPdfBuilder.build(List.of(
                List.of(
                        section("STATE MANAGEMENT"),
                        line("Signals and RxJS Observables are both used for reactive state in this codebase."),
                        line("Signals hold a single current value and notify synchronously on change."),
                        line("Observables represent a stream of values over time and support operators like map, filter, and switchMap.")
                ),
                List.of(
                        section("MY COMPONENT"),
                        line("In my ExerciseListComponent, I used a signal for the selected filter and an Observable"),
                        line("pipeline for the paginated API results, combining them with combineLatest.")
                ),
                List.of(section("STYLING"), line("The project uses Tailwind CSS utility classes exclusively; no custom SCSS files remain."))
        ));

        String documentId = uploadAndAwaitReady(pdf, "frontend-notes.pdf");

        runCase(new EvalCase("a-robustness-vs-question", Flow.GROUNDED_QA,
                "What's the difference between signals and observables in this document?",
                "Must contrast both concepts as described (signals = single current value, synchronous; " +
                        "observables = stream over time, support operators) - not just define one of them."),
                documentId);

        runCase(new EvalCase("a-robustness-in-my-project-qualifier", Flow.GROUNDED_QA,
                "How did I combine signals and observables in my project?",
                "Must reference the ExerciseListComponent example (signal for filter, Observable for paginated " +
                        "results, combineLatest) - not a generic explanation of signals and observables in general."),
                documentId);

        runCase(new EvalCase("a-robustness-unrelated-section", Flow.GROUNDED_QA,
                "What state management library does the styling section recommend?",
                "The Styling section is about Tailwind CSS and does not discuss state management; must not " +
                        "fabricate a connection between them."),
                documentId);

        runCase(new EvalCase("a-robustness-negative-lookup", Flow.GROUNDED_QA,
                "Does this document mention any custom SCSS files?",
                "Must correctly say no custom SCSS files remain (Tailwind utility classes exclusively) - must not hallucinate SCSS usage."),
                documentId);
    }

    private String uploadAndAwaitReady(byte[] pdfBytes, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", pdfBytes);
        Document document = documentService.upload(USER_ID, file);
        documentIdsToCleanUp.add(document.getId());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            Document current = documentRepository.findById(document.getId()).orElseThrow();
            if (current.getStatus() == DocumentStatus.READY) return document.getId();
            if (current.getStatus() == DocumentStatus.FAILED) {
                throw new IllegalStateException("Synthetic document failed to process: " + current.getFailureReason());
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Synthetic document did not finish processing within the timeout.");
    }

    private void runCase(EvalCase evalCase, String documentId) {
        RagResult result = ragService.answer(documentId, evalCase.input());
        assertNotNull(result, "RagService.answer() must never return null");

        String actualResult = """
                documentClaim: %s
                explanation: %s
                insufficientContext: %s
                """.formatted(result.documentClaim(), result.explanation(), result.insufficientContext());

        String deterministicFailure = checkDeterministic(result);
        JudgeVerdict verdict = deterministicFailure != null
                ? JudgeVerdict.fail(deterministicFailure, FailureCategory.DOCUMENT_CLAIM)
                : judge.judge(evalCase, actualResult);

        EvalResultsCollector.record(new EvalCaseResult(evalCase, actualResult, verdict));
    }

    private String checkDeterministic(RagResult result) {
        if (result.documentClaim() == null || result.documentClaim().isBlank()) {
            return "documentClaim was null or blank.";
        }
        if (result.explanation() == null || result.explanation().isBlank()) {
            return "explanation was null or blank.";
        }
        if (result.documentClaim().length() > MAX_DOCUMENT_CLAIM_CHARS) {
            return "documentClaim was suspiciously long (" + result.documentClaim().length() +
                    " chars) - likely a raw dumped excerpt rather than a concise claim.";
        }
        return null;
    }
}
