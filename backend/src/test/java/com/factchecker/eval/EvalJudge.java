package com.factchecker.eval;

import com.factchecker.config.AppProperties;
import com.factchecker.llm.OllamaChatClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

/**
 * An independent judge model, separate from the production chat model, grading one case's real
 * result against its plain-language expectation. Independent in the sense of "a second opinion
 * from a different model" - not claimed to be more capable than the production model.
 */
public class EvalJudge {

    private static final String JUDGE_MODEL = "llama3.1:8b";

    private static final String COMMON_PREAMBLE = """
            You are grading one answer from an AI system against a plain-language expectation of
            what a correct answer must do. Judge semantic correctness and meaning - never require
            exact wording, phrasing, or sentence structure to match the expectation.

            CRITICAL - ground your verdict ONLY in the literal text given below as "ACTUAL RESULT
            PRODUCED BY THE SYSTEM". Do not assume, infer, or imagine content that is not actually
            present in that text. Never fail a case for something you cannot point to specific
            words in the actual result to support (e.g. do not say it "invented a name" unless a
            specific invented name literally appears; do not say it "performed web verification"
            unless the actual result actually shows verification happened). Before writing your
            reasoning, re-read the actual result text and confirm every claim in your reasoning is
            directly supported by words that are literally there.
            """;

    private static final String GROUNDED_QA_RUBRIC = """
            This answer came from a "grounded question answering" system that reads a document and
            answers a question about it. Pass ONLY when the response:
            - answers the user's actual question
            - respects qualifiers/context in the question, such as "in my project" or "in my code"
            - prioritizes the supplied document evidence over generic knowledge
            - does not replace project-specific evidence with generic textbook knowledge when the
              document actually contains a specific, real example
            - produces a concise, coherent documentClaim (a real sentence, not a raw/run-on/garbled
              quote glued together from unrelated fragments)
            - does not elevate irrelevant retrieved text (e.g. a table of contents entry, an
              unrelated section) to primary evidence for the claim
            """;

    private static final String FACT_CHECK_RUBRIC = """
            This answer came from a fact-checking system that extracts a claim from free text,
            searches the web, classifies sources, and summarizes a verdict. Evaluate:
            - whether the extracted claim faithfully represents the input statement
            - whether source classification (supports/contradicts/mixed/not relevant) is a
              reasonable read of the sources described
            - whether the final verdict is consistent with the evidence described, not arbitrary
            - whether the summary accurately reflects the sources and verdict
            - whether the summary avoids unsupported conclusions beyond what the evidence shows
            """;

    private static final String DECOMPOSE_RUBRIC = """
            This answer came from a system that decomposes one statement into independently
            fact-checkable atomic claims. Evaluate:
            - atomicity: each claim is independently checkable on its own
            - completeness: every distinct checkable assertion in the input was captured
            - faithfulness: every claim traces back to something the input actually asserted
            - no invented claims: nothing was added that the input didn't say
            - no unnecessary fragmentation: a single simple claim wasn't needlessly split apart
            - no irrelevant claims: opinions/recommendations/predictions were correctly excluded
            """;

    private static final String RESPONSE_CONTRACT = """
            Respond with ONLY a JSON object of this exact shape, no markdown fences:
            {"verdict": "PASS" | "FAIL", "reasoning": string, "category": string}
            "verdict" must be exactly "PASS" or "FAIL" - never anything else (no "UNCERTAIN").
            "reasoning" is one or two sentences explaining the verdict.
            "category" is required when verdict is "FAIL" (use "NONE" when verdict is "PASS") and
            must be exactly one of: GROUNDING, QUESTION_INTERPRETATION, DOCUMENT_CLAIM, RETRIEVAL,
            HALLUCINATION, EXTRACTION, SOURCE_CLASSIFICATION, SUMMARY, DECOMPOSITION, FORMAT, NONE.
            """;

    private final OllamaChatClient judgeClient;
    private final ObjectMapper objectMapper;

    /** objectMapper must be the real Spring-managed bean (not a bare `new ObjectMapper()`) - it's
     *  configured leniently enough to parse Ollama's response envelope (extra fields alongside
     *  "response"/"verdict" etc.), matching exactly how production OllamaChatClient parses it. */
    public EvalJudge(AppProperties realAppProperties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        AppProperties judgeProperties = new AppProperties();
        AppProperties.Ollama ollama = new AppProperties.Ollama();
        ollama.setBaseUrl(realAppProperties.getOllama().getBaseUrl());
        ollama.setChatModel(JUDGE_MODEL);
        ollama.setEmbeddingModel(realAppProperties.getOllama().getEmbeddingModel());
        judgeProperties.setOllama(ollama);

        this.objectMapper = objectMapper;
        this.judgeClient = new OllamaChatClient(judgeProperties, restClientBuilder, objectMapper);
    }

    public JudgeVerdict judge(EvalCase evalCase, String actualResult) {
        String rubric = switch (evalCase.flow()) {
            case GROUNDED_QA -> GROUNDED_QA_RUBRIC;
            case FACT_CHECK -> FACT_CHECK_RUBRIC;
            case DECOMPOSE -> DECOMPOSE_RUBRIC;
        };
        String system = COMMON_PREAMBLE + "\n" + rubric + "\n" + RESPONSE_CONTRACT;
        String userPrompt = """
                INPUT GIVEN TO THE SYSTEM:
                %s

                EXPECTATION (what a correct answer must do):
                %s

                ACTUAL RESULT PRODUCED BY THE SYSTEM:
                %s
                """.formatted(evalCase.input(), evalCase.expectation(), actualResult);

        String raw;
        try {
            raw = judgeClient.generate(system, userPrompt, true);
        } catch (Exception ex) {
            return JudgeVerdict.fail("Judge call failed: " + ex.getMessage(), FailureCategory.FORMAT);
        }

        try {
            JsonNode node = objectMapper.readTree(raw);
            String verdict = node.path("verdict").asText("");
            String reasoning = node.path("reasoning").asText("(no reasoning given)");
            String categoryRaw = node.path("category").asText("NONE");

            if (!verdict.equals("PASS") && !verdict.equals("FAIL")) {
                return JudgeVerdict.fail("Judge returned an invalid verdict value: '" + verdict + "'", FailureCategory.FORMAT);
            }
            FailureCategory category = parseCategory(categoryRaw);
            return verdict.equals("PASS") ? JudgeVerdict.pass(reasoning) : new JudgeVerdict(false, reasoning, category);
        } catch (Exception ex) {
            return JudgeVerdict.fail("Judge returned unparseable JSON: " + raw, FailureCategory.FORMAT);
        }
    }

    private FailureCategory parseCategory(String raw) {
        try {
            return FailureCategory.valueOf(raw);
        } catch (Exception ex) {
            return FailureCategory.NONE;
        }
    }
}
