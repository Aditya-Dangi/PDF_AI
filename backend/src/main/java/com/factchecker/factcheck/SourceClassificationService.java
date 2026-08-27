package com.factchecker.factcheck;

import com.factchecker.common.JsonUtil;
import com.factchecker.llm.OllamaChatClient;
import com.factchecker.llm.Prompts;
import com.factchecker.websearch.SearXngClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies web sources' stance toward a claim. Classifies ALL sources for one claim in a single
 * LLM call rather than one call per source - on local CPU inference, per-request overhead dominates,
 * so replacing up to ~11 sequential calls with 1 batched call is the single biggest latency win
 * available without changing the underlying model. Source content is truncated before it goes into
 * the prompt for the same reason: prompt-processing time scales with input length.
 */
@Service
public class SourceClassificationService {

    private static final Logger log = LoggerFactory.getLogger(SourceClassificationService.class);
    private static final int MAX_CONTENT_CHARS = 500;

    private final OllamaChatClient llmClient;
    private final SourceQualityService sourceQualityService;
    private final JsonUtil jsonUtil;

    public SourceClassificationService(OllamaChatClient llmClient, SourceQualityService sourceQualityService,
                                        JsonUtil jsonUtil) {
        this.llmClient = llmClient;
        this.sourceQualityService = sourceQualityService;
        this.jsonUtil = jsonUtil;
    }

    public List<SourceEvaluation> classifyBatch(String claim, List<SearXngClient.SearXngResult> results) {
        if (results.isEmpty()) return List.of();

        List<String> titles = results.stream().map(r -> r.title() == null ? "" : r.title()).toList();
        List<String> contents = results.stream().map(this::truncate).toList();

        SourceEvaluation.Stance[] stances = new SourceEvaluation.Stance[results.size()];
        String[] reasonings = new String[results.size()];

        try {
            String response = llmClient.generate(
                    Prompts.SOURCE_BATCH_CLASSIFICATION_SYSTEM,
                    Prompts.sourceBatchClassificationUserPrompt(claim, titles, contents),
                    true
            );
            BatchResult parsed = jsonUtil.fromJson(response, BatchResult.class);
            if (parsed.results() != null) {
                for (SingleResult r : parsed.results()) {
                    if (r.index() == null || r.index() < 0 || r.index() >= results.size()) continue;
                    try {
                        stances[r.index()] = SourceEvaluation.Stance.valueOf(r.stance());
                        reasonings[r.index()] = r.reasoning();
                    } catch (IllegalArgumentException ignored) {
                        // Unrecognized stance value for this index - leave it to the NOT_RELEVANT fallback below.
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Batch source classification failed for {} sources - marking all as NOT_RELEVANT. Cause: {}",
                    results.size(), ex.getMessage());
        }

        List<SourceEvaluation> evaluations = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            SearXngClient.SearXngResult result = results.get(i);
            AuthorityTier tier = sourceQualityService.classify(result.url());
            SourceEvaluation.Stance stance = stances[i] != null ? stances[i] : SourceEvaluation.Stance.NOT_RELEVANT;
            String reasoning = reasonings[i];

            String snippet = reasoning != null && !reasoning.isBlank()
                    ? reasoning
                    : (result.content() != null && result.content().length() > 240 ? result.content().substring(0, 240) + "..." : result.content());

            evaluations.add(new SourceEvaluation(result.url(), result.title(), snippet, stance, tier, result.publishedDate()));
        }
        return evaluations;
    }

    private String truncate(SearXngClient.SearXngResult result) {
        String content = result.content();
        if (content == null) return "";
        return content.length() > MAX_CONTENT_CHARS ? content.substring(0, MAX_CONTENT_CHARS) + "..." : content;
    }

    private record SingleResult(Integer index, String stance, String reasoning) {
    }

    private record BatchResult(List<SingleResult> results) {
    }
}
