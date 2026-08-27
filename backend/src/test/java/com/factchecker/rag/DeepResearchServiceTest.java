package com.factchecker.rag;

import com.factchecker.common.JsonUtil;
import com.factchecker.config.AppProperties;
import com.factchecker.domain.Chunk;
import com.factchecker.llm.LlmUnavailableException;
import com.factchecker.llm.OllamaChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Termination tests for Quality mode's research loop.
 *
 * <p>This class exists because the loop is driven partly by a local 8B model deciding "am I done?",
 * which is exactly the kind of control flow that wanders. Each stop condition is asserted
 * independently, and every one must still produce an answer - Quality mode is never allowed to turn
 * a slow request into a failed one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepResearchServiceTest {

    private static final String DOC = "doc-1";
    private static final String QUESTION = "how does hashing work here?";

    @Mock private RagService ragService;
    @Mock private OllamaChatClient llmClient;

    /** Production defaults are 3 rounds / 180s; the loop's behavior is identical at a tiny budget,
     *  and this keeps the time-budget test from actually costing three minutes of wall clock. */
    private static final int MAX_ROUNDS = 3;
    private static final long TIME_BUDGET_MS = 300;

    private DeepResearchService service;

    @BeforeEach
    void setUp() {
        service = newService(TIME_BUDGET_MS);
        when(ragService.answerWithContext(anyString(), any())).thenReturn(answer("a draft answer"));
    }

    private DeepResearchService newService(long timeBudgetMs) {
        AppProperties props = new AppProperties();
        AppProperties.DeepResearch cfg = new AppProperties.DeepResearch();
        cfg.setMaxRounds(MAX_ROUNDS);
        cfg.setTimeBudgetMs(timeBudgetMs);
        props.setDeepResearch(cfg);
        return new DeepResearchService(ragService, llmClient, new JsonUtil(new ObjectMapper()), props);
    }

    @Test
    @DisplayName("stops as soon as gap analysis reports the answer is complete")
    void stopsWhenGapAnalysisSaysComplete() {
        when(ragService.retrieveTopChunks(anyString(), anyString())).thenReturn(List.of(chunk("c1")));
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenReturn("{\"complete\": true, \"missingQuery\": \"\"}");

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.COMPLETE);
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.result()).isNotNull();
    }

    @Test
    @DisplayName("stops at the round cap when gap analysis keeps asking for more")
    void stopsAtRoundCapWhenNeverComplete() {
        // Each round must surface something new, or NO_NEW_EVIDENCE would stop it first - this test
        // is specifically about the cap holding when evidence keeps arriving.
        when(ragService.retrieveTopChunks(anyString(), anyString()))
                .thenReturn(List.of(chunk("c1")), List.of(chunk("c2")), List.of(chunk("c3")), List.of(chunk("c4")));
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenReturn("{\"complete\": false, \"missingQuery\": \"more detail\"}");

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.ROUND_CAP);
        assertThat(result.rounds()).isEqualTo(MAX_ROUNDS);
        verify(ragService, times(MAX_ROUNDS)).retrieveTopChunks(anyString(), anyString());
    }

    @Test
    @DisplayName("stops when a round surfaces no evidence that was not already seen")
    void stopsWhenNoNewEvidence() {
        // The deterministic guardrail: the same chunk comes back every round, so the document has
        // nothing further to give regardless of what the model claims is missing.
        when(ragService.retrieveTopChunks(anyString(), anyString())).thenReturn(List.of(chunk("c1")));
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenReturn("{\"complete\": false, \"missingQuery\": \"more detail\"}");

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.NO_NEW_EVIDENCE);
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.result()).isNotNull();
        verify(ragService, atMost(2)).retrieveTopChunks(anyString(), anyString());
    }

    @Test
    @DisplayName("malformed gap analysis ends the loop gracefully with the best draft so far")
    void malformedGapAnalysisTerminatesGracefully() {
        when(ragService.retrieveTopChunks(anyString(), anyString())).thenReturn(List.of(chunk("c1")));
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenReturn("not json at all");

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.GAP_ANALYSIS_UNAVAILABLE);
        assertThat(result.result().explanation()).isEqualTo("a draft answer");
        assertThat(result.rounds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing gap analysis call ends the loop gracefully rather than failing the request")
    void erroringGapAnalysisTerminatesGracefully() {
        when(ragService.retrieveTopChunks(anyString(), anyString())).thenReturn(List.of(chunk("c1")));
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenThrow(new LlmUnavailableException("ollama is down"));

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.GAP_ANALYSIS_UNAVAILABLE);
        assertThat(result.result()).isNotNull();
    }

    @Test
    @DisplayName("gap analysis that says 'incomplete' without saying what is missing also stops")
    void incompleteWithoutMissingQueryTerminates() {
        // Otherwise the next round would re-run the identical query and spin.
        when(ragService.retrieveTopChunks(anyString(), anyString())).thenReturn(List.of(chunk("c1")));
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenReturn("{\"complete\": false, \"missingQuery\": \"\"}");

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.GAP_ANALYSIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("stops immediately when retrieval finds nothing relevant enough to answer from")
    void stopsOnInsufficientContext() {
        when(ragService.retrieveTopChunks(anyString(), anyString())).thenReturn(List.of());
        when(ragService.answerWithContext(anyString(), any()))
                .thenReturn(new RagResult("no text", "nothing indexed", true, 0, 0, List.of()));

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.INSUFFICIENT_CONTEXT);
        assertThat(result.rounds()).isEqualTo(1);
        verify(llmClient, never()).generate(anyString(), anyString(), any(Boolean.class));
    }

    @Test
    @DisplayName("the time budget is global across the request, not per round")
    void timeBudgetIsGlobalAcrossTheWholeRequest() {
        // Each round burns most of the budget. Per-round budgeting would let all 3 rounds run;
        // a global budget must stop after the second round's check.
        when(ragService.retrieveTopChunks(anyString(), anyString()))
                .thenReturn(List.of(chunk("c1")), List.of(chunk("c2")), List.of(chunk("c3")));
        when(ragService.answerWithContext(anyString(), any())).thenAnswer(inv -> {
            Thread.sleep(TIME_BUDGET_MS / 2 + 50);
            return answer("a draft answer");
        });
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenReturn("{\"complete\": false, \"missingQuery\": \"more detail\"}");

        DeepResearchResult result = service.research(DOC, QUESTION);

        assertThat(result.stopReason()).isEqualTo(DeepResearchResult.StopReason.TIME_BUDGET);
        assertThat(result.rounds()).isLessThan(MAX_ROUNDS);
        assertThat(result.result()).isNotNull();
    }

    @Test
    @DisplayName("accumulates evidence across rounds instead of answering from the latest alone")
    void accumulatesEvidenceAcrossRounds() {
        when(ragService.retrieveTopChunks(anyString(), anyString()))
                .thenReturn(List.of(chunk("c1")), List.of(chunk("c2")), List.of(chunk("c3")));
        when(llmClient.generate(anyString(), anyString(), any(Boolean.class)))
                .thenReturn("{\"complete\": false, \"missingQuery\": \"more detail\"}");

        service.research(DOC, QUESTION);

        // Final round answers from all three accumulated chunks, not just the third.
        verify(ragService).answerWithContext(anyString(), org.mockito.ArgumentMatchers.argThat(l -> l.size() == 3));
    }

    private static RagResult answer(String explanation) {
        return new RagResult("claim", explanation, false, 80, 90, List.of());
    }

    private static RetrievedChunk chunk(String id) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setPage(1);
        c.setText("text of " + id);
        c.setRectsJson("[]");
        return new RetrievedChunk(c, 0.7);
    }
}
