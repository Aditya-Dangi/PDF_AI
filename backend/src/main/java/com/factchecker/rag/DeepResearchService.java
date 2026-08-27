package com.factchecker.rag;

import com.factchecker.common.JsonUtil;
import com.factchecker.config.AppProperties;
import com.factchecker.llm.OllamaChatClient;
import com.factchecker.llm.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quality mode: an iterative gap-filling research loop over a single document.
 *
 * <p>Each round retrieves evidence, re-answers against <em>everything</em> gathered so far, then
 * asks what is still missing and searches for that next. Fast mode is one pass of this with no gap
 * step; both answer through {@link RagService#answerWithContext}, so they cannot drift apart.
 *
 * <p>A loop driven by a local 8B model deciding "am I done?" could otherwise wander, so termination
 * never depends on that judgement alone. Four independent conditions stop it, and every one of them
 * - plus any failure inside a round - returns the best draft produced so far. Quality mode does not
 * fail a request; at worst it answers with less research than it wanted.
 */
@Service
public class DeepResearchService {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchService.class);

    private final RagService ragService;
    private final OllamaChatClient llmClient;
    private final JsonUtil jsonUtil;
    /** Hard ceiling on rounds. */
    private final int maxRounds;
    /**
     * Wall-clock budget for the WHOLE research request, not per round. Checked before starting each
     * round, so a request that has already spent its budget stops rather than beginning work that
     * would overrun it further.
     */
    private final long timeBudgetMs;

    public DeepResearchService(RagService ragService, OllamaChatClient llmClient, JsonUtil jsonUtil,
                                AppProperties appProperties) {
        this.ragService = ragService;
        this.llmClient = llmClient;
        this.jsonUtil = jsonUtil;
        this.maxRounds = appProperties.getDeepResearch().getMaxRounds();
        this.timeBudgetMs = appProperties.getDeepResearch().getTimeBudgetMs();
    }

    public DeepResearchResult research(String documentId, String question) {
        long startedAt = System.currentTimeMillis();
        // Insertion-ordered and keyed by chunk id: dedupes across rounds while keeping a stable,
        // reproducible evidence set to re-answer from.
        Map<String, RetrievedChunk> seen = new LinkedHashMap<>();
        RagResult best = null;
        int rounds = 0;
        String query = question;

        for (int round = 1; round <= maxRounds; round++) {
            if (System.currentTimeMillis() - startedAt > timeBudgetMs) {
                return finish(best, rounds, DeepResearchResult.StopReason.TIME_BUDGET, documentId, question);
            }

            List<RetrievedChunk> retrieved = ragService.retrieveTopChunks(documentId, query);
            boolean addedSomething = false;
            for (RetrievedChunk chunk : retrieved) {
                if (seen.putIfAbsent(chunk.chunk().getId(), chunk) == null) addedSomething = true;
            }

            // Deterministic guardrail, and the most important one: it does not rely on the model
            // reporting that it is finished. If a round surfaces nothing new, the document has
            // nothing further to give and more rounds cannot change the answer.
            if (round > 1 && !addedSomething) {
                return finish(best, rounds, DeepResearchResult.StopReason.NO_NEW_EVIDENCE, documentId, question);
            }

            best = ragService.answerWithContext(question, rankedEvidence(seen));
            rounds = round;

            if (best.insufficientContext()) {
                return finish(best, rounds, DeepResearchResult.StopReason.INSUFFICIENT_CONTEXT, documentId, question);
            }
            if (round == maxRounds) {
                return finish(best, rounds, DeepResearchResult.StopReason.ROUND_CAP, documentId, question);
            }

            GapAnalysis gap = analyzeGap(question, best.explanation());
            if (gap == null || (!gap.complete() && isBlank(gap.missingQuery()))) {
                // Unparseable, errored, or "incomplete but I can't say what's missing" - all mean
                // the loop has nothing actionable left, so stop with what we have rather than
                // retrying blindly or failing the request.
                return finish(best, rounds, DeepResearchResult.StopReason.GAP_ANALYSIS_UNAVAILABLE, documentId, question);
            }
            if (gap.complete()) {
                return finish(best, rounds, DeepResearchResult.StopReason.COMPLETE, documentId, question);
            }
            query = gap.missingQuery();
        }

        return finish(best, rounds, DeepResearchResult.StopReason.ROUND_CAP, documentId, question);
    }

    /**
     * Guarantees a non-null result. The only way {@code best} is null is a stop before the first
     * answer was ever drafted (a budget already exhausted on entry), in which case a single plain
     * pass still gives the user an answer instead of an error.
     */
    private DeepResearchResult finish(RagResult best, int rounds, DeepResearchResult.StopReason reason,
                                       String documentId, String question) {
        if (best == null) {
            log.warn("Deep research for document {} stopped ({}) before drafting; falling back to a single pass",
                    documentId, reason);
            return new DeepResearchResult(ragService.answer(documentId, question), 1, reason);
        }
        return new DeepResearchResult(best, rounds, reason);
    }

    /** Highest-similarity evidence first, so the prompt's most relevant context leads. */
    private List<RetrievedChunk> rankedEvidence(Map<String, RetrievedChunk> seen) {
        List<RetrievedChunk> all = new ArrayList<>(seen.values());
        all.sort(Comparator.comparingDouble(RetrievedChunk::similarity).reversed());
        return all;
    }

    /** Returns null when the gap step cannot be trusted - the caller treats that as "stop". */
    private GapAnalysis analyzeGap(String question, String draftAnswer) {
        try {
            String raw = llmClient.generate(
                    Prompts.GAP_ANALYSIS_SYSTEM,
                    Prompts.gapAnalysisUserPrompt(question, draftAnswer),
                    true
            );
            return jsonUtil.fromJson(raw, GapAnalysis.class);
        } catch (Exception ex) {
            log.warn("Gap analysis failed, ending deep research with the current draft: {}", ex.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    record GapAnalysis(boolean complete, String missingQuery) {
    }
}
