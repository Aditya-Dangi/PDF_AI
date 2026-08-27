package com.factchecker.rag;

import com.factchecker.common.JsonUtil;
import com.factchecker.domain.Chunk;
import com.factchecker.embedding.EmbeddingService;
import com.factchecker.embedding.VectorMath;
import com.factchecker.llm.OllamaChatClient;
import com.factchecker.llm.Prompts;
import com.factchecker.repository.ChunkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the "understand the document" flow: retrieve relevant chunks (deterministic vector
 * search, no LLM call), then ask the LLM to answer using ONLY those chunks. Both quality scores
 * (retrieval confidence, document fidelity) are computed deterministically from embeddings, not
 * self-reported by the model.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int TOP_K = 5;
    /** Below this raw cosine similarity, we don't even bother calling the LLM - nothing relevant was found. */
    private static final double MIN_RELEVANCE_THRESHOLD = 0.22;

    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final OllamaChatClient llmClient;
    private final JsonUtil jsonUtil;

    public RagService(ChunkRepository chunkRepository, EmbeddingService embeddingService,
                       OllamaChatClient llmClient, JsonUtil jsonUtil) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.llmClient = llmClient;
        this.jsonUtil = jsonUtil;
    }

    /**
     * Deterministic retrieval only (no LLM call): the top-K chunks by cosine similarity to the
     * query text, ranked highest first. Shared by the grounded-answer flow below and by
     * ClaimVerificationService, which needs the same "find the document evidence for this text"
     * step but for a claim rather than a question.
     */
    public List<RetrievedChunk> retrieveTopChunks(String documentId, String queryText) {
        List<Chunk> allChunks = chunkRepository.findByDocumentIdOrderByChunkOrderAsc(documentId);
        if (allChunks.isEmpty()) return List.of();

        double[] queryEmbedding = embeddingService.embed(queryText);

        return allChunks.stream()
                .map(chunk -> new RetrievedChunk(chunk, VectorMath.cosineSimilarity(
                        queryEmbedding, jsonUtil.fromJson(chunk.getEmbeddingJson(), new TypeReference<double[]>() {}))))
                .sorted(Comparator.comparingDouble(RetrievedChunk::similarity).reversed())
                .limit(TOP_K)
                .toList();
    }

    public RagResult answer(String documentId, String question) {
        List<RetrievedChunk> ranked = retrieveTopChunks(documentId, question);
        if (ranked.isEmpty()) {
            return new RagResult(
                    "This document has no extractable text yet.",
                    "The document could not be indexed, so there is nothing to search.",
                    true, 0, 0, List.of()
            );
        }

        double topSimilarity = ranked.get(0).similarity();
        double retrievalConfidence = VectorMath.toConfidencePercent(topSimilarity);

        if (topSimilarity < MIN_RELEVANCE_THRESHOLD) {
            return new RagResult(
                    "The document does not appear to address this question.",
                    "No sufficiently relevant passage was found in the document for this question.",
                    true, retrievalConfidence, 0, ranked
            );
        }

        String context = buildContext(ranked);
        String rawResponse = llmClient.generate(
                Prompts.GROUNDED_QA_SYSTEM,
                Prompts.groundedQaUserPrompt(question, context),
                true
        );

        QaLlmResult parsed;
        try {
            parsed = jsonUtil.fromJson(rawResponse, QaLlmResult.class);
        } catch (Exception ex) {
            log.error("Failed to parse grounded QA response as JSON: {}", rawResponse, ex);
            throw new IllegalStateException("The model returned an unexpected response format.");
        }

        double fidelityConfidence;
        if (parsed.insufficientContext()) {
            fidelityConfidence = 0;
        } else {
            double[] claimEmbedding = embeddingService.embed(parsed.documentClaim());
            double[] contextEmbedding = embeddingService.embed(context);
            fidelityConfidence = VectorMath.toConfidencePercent(VectorMath.cosineSimilarity(claimEmbedding, contextEmbedding));
        }

        return new RagResult(
                parsed.documentClaim(),
                parsed.explanation(),
                parsed.insufficientContext(),
                retrievalConfidence,
                fidelityConfidence,
                ranked
        );
    }

    /** Public - reused by ClaimVerificationService to build the same "[Page N] text" evidence context. */
    public String buildContext(List<RetrievedChunk> ranked) {
        return ranked.stream()
                .map(rc -> "[Page %d] %s".formatted(rc.chunk().getPage(), rc.chunk().getText()))
                .collect(Collectors.joining("\n\n"));
    }

    private record QaLlmResult(String documentClaim, String explanation, boolean insufficientContext) {
    }
}
