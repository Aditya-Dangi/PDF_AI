package com.factchecker.service;

import com.factchecker.llm.OllamaChatClient;
import com.factchecker.llm.Prompts;
import org.springframework.stereotype.Service;

/** Plain summarization - deliberately NOT the fact-check pipeline (no claim extraction, no web
 *  search, no verdict). Backs "Summarize" from the PDF selection toolbar and the standalone
 *  summarize flow: the user selected/dragged something and just wants to know what it says. */
@Service
public class SummarizationService {

    private final OllamaChatClient llmClient;

    public SummarizationService(OllamaChatClient llmClient) {
        this.llmClient = llmClient;
    }

    public String summarize(String text) {
        return llmClient.generate(Prompts.SUMMARIZE_SYSTEM, Prompts.summarizeUserPrompt(text), false).trim();
    }
}
