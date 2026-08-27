package com.factchecker.claim;

import com.factchecker.common.JsonUtil;
import com.factchecker.llm.OllamaChatClient;
import com.factchecker.llm.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Breaks a statement into independently fact-checkable atomic claims (e.g. "X reduces disease Y by
 * 60% and is the safest treatment" -> effectiveness claim + magnitude claim + safety claim). Reused
 * for two purposes: decomposing an existing answer's documentClaim, and detecting claims per chunk
 * during a whole-document audit (same prompt, same "extract checkable claims from this text" job).
 */
@Service
public class ClaimDecompositionService {

    private static final Logger log = LoggerFactory.getLogger(ClaimDecompositionService.class);

    private final OllamaChatClient llmClient;
    private final JsonUtil jsonUtil;

    public ClaimDecompositionService(OllamaChatClient llmClient, JsonUtil jsonUtil) {
        this.llmClient = llmClient;
        this.jsonUtil = jsonUtil;
    }

    public List<AtomicClaim> decompose(String sourceText) {
        String response = llmClient.generate(
                Prompts.CLAIM_DECOMPOSITION_SYSTEM,
                Prompts.claimDecompositionUserPrompt(sourceText),
                true
        );
        try {
            DecompositionResult parsed = jsonUtil.fromJson(response, DecompositionResult.class);
            return parsed.claims() == null ? List.of() : parsed.claims();
        } catch (Exception ex) {
            log.warn("Failed to parse claim decomposition response, treating as no checkable claims. Response: {}", response, ex);
            return List.of();
        }
    }

    private record DecompositionResult(List<AtomicClaim> claims) {
    }
}
