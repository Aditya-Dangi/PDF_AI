package com.factchecker.embedding;

import com.factchecker.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Computes text embeddings via a locally running Ollama instance (free, no API key). Ollama must
 * be running with the configured embedding model pulled (e.g. `ollama pull nomic-embed-text`).
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public EmbeddingService(AppProperties appProperties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        // A batch of many chunks embedded in one call can take a while on local CPU inference.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(180_000);
        requestFactory.setConnectTimeout(10_000);

        this.restClient = restClientBuilder
                .baseUrl(appProperties.getOllama().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.model = appProperties.getOllama().getEmbeddingModel();
        this.objectMapper = objectMapper;
    }

    public double[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * Embeds many texts in a single Ollama call via its batch-capable /api/embed endpoint, instead
     * of one HTTP round trip per text - the dominant cost when ingesting a large document (a
     * multi-hundred-page PDF can produce hundreds of chunks, each previously a separate request).
     */
    public List<double[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        try {
            // byte[].class uses ByteArrayHttpMessageConverter, which explicitly supports */* - the
            // only built-in converter that can't be rejected by Ollama's response content-type
            // (observed as application/octet-stream under load, which even String.class rejects).
            byte[] raw = restClient.post()
                    .uri("/api/embed")
                    .body(Map.of("model", model, "input", texts))
                    .retrieve()
                    .body(byte[].class);
            OllamaEmbedResponse response = objectMapper.readValue(raw, OllamaEmbedResponse.class);

            if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
                throw new IllegalStateException("Ollama returned an unexpected number of embeddings");
            }
            return response.embeddings().stream()
                    .map(values -> values.stream().mapToDouble(Double::doubleValue).toArray())
                    .toList();
        } catch (Exception ex) {
            log.error("Embedding request failed against Ollama at model '{}': {}", model, ex.getMessage());
            throw new EmbeddingUnavailableException(
                    "Local embedding model is unavailable. Make sure Ollama is running (ollama serve) " +
                            "and the model '" + model + "' is pulled (ollama pull " + model + ").", ex);
        }
    }

    private record OllamaEmbedResponse(List<List<Double>> embeddings) {
    }
}
