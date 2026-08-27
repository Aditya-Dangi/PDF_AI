package com.factchecker.factcheck;

import com.factchecker.common.JsonUtil;
import com.factchecker.domain.Verdict;
import com.factchecker.domain.WebCache;
import com.factchecker.llm.OllamaChatClient;
import com.factchecker.llm.Prompts;
import com.factchecker.repository.WebCacheRepository;
import com.factchecker.websearch.SearXngClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates fact-checking: extract a checkable claim, search the web (self-hosted SearXNG),
 * have the LLM classify each source's stance toward the claim (a narrow judgment, not a truth
 * verdict), then compute a verdict and confidence deterministically (see VerdictCalculator). Web
 * search + source classification results are cached by normalized claim so repeated fact-checks
 * of the same claim don't re-run search/classification.
 */
@Service
public class FactCheckService {

    private static final Logger log = LoggerFactory.getLogger(FactCheckService.class);
    private static final int MAX_SOURCES = 6;

    private final SearXngClient searXngClient;
    private final OllamaChatClient llmClient;
    private final SourceClassificationService sourceClassificationService;
    private final VerdictCalculator verdictCalculator;
    private final WebCacheRepository webCacheRepository;
    private final JsonUtil jsonUtil;

    public FactCheckService(SearXngClient searXngClient, OllamaChatClient llmClient,
                             SourceClassificationService sourceClassificationService, VerdictCalculator verdictCalculator,
                             WebCacheRepository webCacheRepository, JsonUtil jsonUtil) {
        this.searXngClient = searXngClient;
        this.llmClient = llmClient;
        this.sourceClassificationService = sourceClassificationService;
        this.verdictCalculator = verdictCalculator;
        this.webCacheRepository = webCacheRepository;
        this.jsonUtil = jsonUtil;
    }

    public FactCheckResult factCheck(String rawText) {
        ClaimExtractionResult extraction = extractClaim(rawText);

        if (!extraction.checkable()) {
            return new FactCheckResult(
                    extraction.claim() == null || extraction.claim().isBlank() ? rawText : extraction.claim(),
                    extraction.type(),
                    false,
                    Verdict.INSUFFICIENT_EVIDENCE,
                    0,
                    "This statement is a " + extraction.type().toLowerCase() + ", not an independently " +
                            "checkable factual claim, so no web verification was performed. " + extraction.reason(),
                    List.of()
            );
        }

        String claim = extraction.claim();
        String cacheKey = hash(claim.toLowerCase().trim());

        CachedResult cached = webCacheRepository.findByClaimHash(cacheKey)
                .map(wc -> jsonUtil.fromJson(wc.getResultJson(), CachedResult.class))
                .orElse(null);

        List<SourceEvaluation> sources;
        String summary;

        if (cached != null) {
            sources = cached.sources();
            summary = cached.summary();
        } else {
            sources = searchAndClassify(claim);
            summary = summarize(claim, sources);
            webCacheRepository.save(newCacheEntity(cacheKey, new CachedResult(sources, summary)));
        }

        VerdictCalculator.VerdictOutcome outcome = verdictCalculator.compute(sources);

        return new FactCheckResult(claim, extraction.type(), true, outcome.verdict(), outcome.webConfidence(), summary, sources);
    }

    private ClaimExtractionResult extractClaim(String rawText) {
        String response = llmClient.generate(
                Prompts.CLAIM_EXTRACTION_SYSTEM,
                Prompts.claimExtractionUserPrompt(rawText),
                true
        );
        try {
            return jsonUtil.fromJson(response, ClaimExtractionResult.class);
        } catch (Exception ex) {
            log.error("Failed to parse claim extraction response: {}", response, ex);
            throw new IllegalStateException("The model returned an unexpected response format during claim extraction.");
        }
    }

    private List<SourceEvaluation> searchAndClassify(String claim) {
        List<SearXngClient.SearXngResult> results = searXngClient.search(claim, MAX_SOURCES);
        return sourceClassificationService.classifyBatch(claim, results);
    }

    private String summarize(String claim, List<SourceEvaluation> sources) {
        if (sources.stream().allMatch(s -> s.stance() == SourceEvaluation.Stance.NOT_RELEVANT)) {
            return "No web source found that directly addresses this claim, so no verification could be performed.";
        }

        String stanceSummary = sources.stream()
                .map(s -> "- [%s, %s authority] %s: %s".formatted(s.title(), s.authorityTier(), s.stance(), s.snippet()))
                .collect(Collectors.joining("\n"));

        return llmClient.generate(
                Prompts.FACT_CHECK_SUMMARY_SYSTEM,
                Prompts.factCheckSummaryUserPrompt(claim, stanceSummary),
                false
        );
    }

    private WebCache newCacheEntity(String hash, CachedResult result) {
        WebCache cache = new WebCache();
        cache.setClaimHash(hash);
        cache.setResultJson(jsonUtil.toJson(result));
        return cache;
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record CachedResult(List<SourceEvaluation> sources, String summary) {
    }
}
