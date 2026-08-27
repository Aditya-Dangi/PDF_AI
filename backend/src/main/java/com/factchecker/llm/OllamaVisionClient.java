package com.factchecker.llm;

import com.factchecker.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Thin REST client for a local Ollama vision-capable model (e.g. llava), used only as a fallback
 * when OCR finds too little text in a user-selected image region (see ImageQueryService) - a pure
 * diagram or chart with no embedded text still needs *some* description to feed into the existing
 * ask/fact-check pipelines. Mirrors OllamaChatClient's request/parsing approach deliberately.
 */
@Component
public class OllamaVisionClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaVisionClient.class);
    private static final int MAX_OUTPUT_TOKENS = 400;

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public OllamaVisionClient(AppProperties appProperties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // Vision inference is slower than text-only, and this is a request the user is actively
        // waiting on (not a background job) - generous but bounded.
        requestFactory.setReadTimeout(120_000);
        requestFactory.setConnectTimeout(10_000);

        this.restClient = restClientBuilder
                .baseUrl(appProperties.getOllama().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.model = appProperties.getOllama().getVisionModel();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return model != null && !model.isBlank();
    }

    /** Describes the given image for use as a stand-in "question"/"claim" text in the existing
     *  ask/fact-check pipelines, when OCR couldn't find enough real text to use instead. */
    public String describe(byte[] imageBytes) {
        if (!isConfigured()) {
            throw new LlmUnavailableException("No Ollama vision model is configured (app.ollama.vision-model).");
        }

        String prompt = """
                Describe exactly what this image shows, in plain factual language a reader could act
                on without seeing the image themselves - the type of visual (diagram, chart, table,
                screenshot, photo, etc.), its labeled parts, any text visible in it, and the
                relationship or process it depicts. Be concise but complete. Do not speculate about
                anything not actually visible in the image.
                """;

        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", prompt,
                "images", List.of(Base64.getEncoder().encodeToString(imageBytes)),
                "stream", false,
                "options", Map.of("temperature", 0.2, "num_predict", MAX_OUTPUT_TOKENS)
        );

        try {
            // Same byte[].class extraction as OllamaChatClient - Ollama doesn't always send a
            // content-type Spring's message converters accept for JSON.
            byte[] raw = restClient.post()
                    .uri("/api/generate")
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            OllamaGenerateResponse response = objectMapper.readValue(raw, OllamaGenerateResponse.class);

            if (response == null || response.response() == null || response.response().isBlank()) {
                throw new IllegalStateException("Ollama returned an empty response");
            }
            return response.response().trim();
        } catch (Exception ex) {
            log.error("Ollama vision request failed against model '{}': {}", model, ex.getMessage());
            throw new LlmUnavailableException(
                    "Local vision model request failed. Make sure Ollama is running and the model '" +
                            model + "' is pulled (ollama pull " + model + "). Cause: " + ex.getMessage(), ex);
        }
    }

    private record OllamaGenerateResponse(String response) {
    }
}
