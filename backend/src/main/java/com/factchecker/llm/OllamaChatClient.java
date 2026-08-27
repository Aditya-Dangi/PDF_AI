package com.factchecker.llm;

import com.factchecker.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Thin REST client for a local Ollama chat model (e.g. llama3.2). Fully local, free, no API key,
 * no rate limits - the trade-off is lower reliability at structured output and weaker reasoning
 * than a hosted frontier model, which is why jsonMode uses Ollama's "format": "json" constrained
 * decoding rather than trusting the model to follow the instruction unaided.
 */
@Component
public class OllamaChatClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatClient.class);
    /** Every prompt in this app expects a short structured answer (a JSON object/array of a few
     *  fields, or a couple of sentences) - capping output length bounds the worst case and guards
     *  against small local models occasionally getting stuck in runaway/repetitive generation
     *  (observed generating 18,000+ tokens for what should have been a one-sentence answer, which
     *  also starves every other request queued behind it on Ollama's single-slot worker). */
    private static final int MAX_OUTPUT_TOKENS = 800;

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public OllamaChatClient(AppProperties appProperties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        // Local CPU inference can be considerably slower than a hosted API, especially for the
        // first request after a model loads into memory - use a generous read timeout.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(120_000);
        requestFactory.setConnectTimeout(10_000);

        this.restClient = restClientBuilder
                .baseUrl(appProperties.getOllama().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.model = appProperties.getOllama().getChatModel();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return model != null && !model.isBlank();
    }

    public String generate(String systemInstruction, String userPrompt, boolean jsonMode) {
        if (!isConfigured()) {
            throw new LlmUnavailableException("No Ollama chat model is configured (app.ollama.chat-model).");
        }

        Map<String, Object> body = jsonMode
                ? Map.of(
                        "model", model,
                        "system", systemInstruction,
                        "prompt", userPrompt,
                        "format", "json",
                        "stream", false,
                        "options", Map.of("temperature", 0.1, "num_predict", MAX_OUTPUT_TOKENS)
                )
                : Map.of(
                        "model", model,
                        "system", systemInstruction,
                        "prompt", userPrompt,
                        "stream", false,
                        "options", Map.of("temperature", 0.3, "num_predict", MAX_OUTPUT_TOKENS)
                );

        try {
            // Ollama doesn't always send a content-type Spring's message converters accept for JSON
            // (observed as application/octet-stream, especially under concurrent load), and even
            // String.class extraction is still media-type-gated. byte[].class uses
            // ByteArrayHttpMessageConverter, which explicitly declares support for */* - the only
            // built-in converter that can't be rejected by content-type.
            byte[] raw = restClient.post()
                    .uri("/api/generate")
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            OllamaGenerateResponse response = objectMapper.readValue(raw, OllamaGenerateResponse.class);

            if (response == null || response.response() == null || response.response().isBlank()) {
                throw new IllegalStateException("Ollama returned an empty response");
            }
            return response.response();
        } catch (Exception ex) {
            log.error("Ollama chat request failed against model '{}': {}", model, ex.getMessage());
            throw new LlmUnavailableException(
                    "Local LLM request failed. Make sure Ollama is running (ollama serve) and the model '" +
                            model + "' is pulled (ollama pull " + model + "). Cause: " + ex.getMessage(), ex);
        }
    }

    private record OllamaGenerateResponse(String response) {
    }
}
