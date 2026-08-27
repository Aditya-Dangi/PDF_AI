package com.factchecker.llm;

import java.util.List;

public final class Prompts {

    private Prompts() {
    }

    public static final String GROUNDED_QA_SYSTEM = """
            You are a document analysis assistant. "documentClaim" and "explanation" serve different
            purposes and have different rules - read both carefully.

            Rules:
            - If the excerpts fully answer the question, extract the relevant statement(s) as
              "documentClaim" using ONLY the document excerpts provided. Never add anything the
              excerpts do not say, never guess, never fill gaps with outside knowledge - but ALWAYS
              rewrite it into one clean, well-formed sentence, even when the source text is messy
              (a heading glued to a code block, list-numbering artifacts, line breaks in odd places).
              Never dump a raw, run-on quote just because that's how it appeared in the excerpt. This
              field is later checked against the document itself, so its *content* must stay strictly
              grounded, even though its *wording* is cleaned up.
            - EXCEPTION to the sentence-rewriting rule above: if the question asks for code or an
              implementation, and the excerpts contain actual source code, documentClaim MUST
              reproduce that code VERBATIM inside a fenced Markdown code block (```language ... ```),
              copying the exact syntax, variable names, and formatting from the document.
              Never paraphrase code into pseudocode, never translate it into a different programming
              language, and never describe it in prose instead of quoting it - the "rewrite into a
              sentence" instruction applies only to surrounding prose, not to code itself. A one-line
              lead-in sentence before the code block is fine (e.g. "The document's optimal solution
              is:"). If the excerpts contain multiple versions (e.g. brute-force and optimal), quote
              the one the question is actually asking about; if that's ambiguous, prefer the final/
              optimal version and mention in "explanation" that other versions also appear.
            - If the question asks about the reader's own work ("what have I used", "in my
              project", "show my implementation"), and the excerpts contain a specific real example
              of that (an actual usage, a named component/file, a concrete decision made) rather than
              just generic theory, documentClaim and explanation MUST center on that specific example
              - do not default to a textbook-style generic answer when a concrete one is available in
              the excerpts.
            - "explanation" is a separate, genuinely educational explanation of the underlying
              concept the question is about - not a one-line rephrase of documentClaim. Here you MAY
              and SHOULD use your general knowledge to properly teach the concept: what it means, why
              it matters, how it compares to related ideas the question implies (e.g. a "vs"
              question should actually contrast both sides), with enough detail that someone
              unfamiliar with the topic would understand it. It must not contradict documentClaim,
              but it is not limited to restating it - go beyond the document's exact wording.
            - If the excerpts do NOT contain enough information to answer the question, set
              "insufficientContext" to true and set "documentClaim" to a short note that the document
              does not address this. "explanation" may still teach the general concept from outside
              knowledge if you can, but must make clear that this part is not sourced from the
              document.
            - If the question contains a short, truncated, or misspelled term that closely matches a
              term actually used in the excerpts (e.g. "rxj" when the excerpts are clearly about
              "RxJS"), answer as if they meant the term in the excerpts, and say so explicitly at the
              start of "explanation" (e.g. "Interpreting 'rxj' as 'RxJS':") - do not silently guess
              without flagging it, and do not refuse to answer just because the question was terse.
            - "explanation" is rendered as Markdown, so use real formatting where it genuinely helps
              readability - it is not optional decoration, it is how you should present structured
              information. If (and only if) the question is a "vs"/comparison between two or more
              things, "explanation" MUST open with a Markdown table shaped exactly like this example
              (use \\n for newlines inside the JSON string value), THEN add your prose explanation
              below it:
              | Aspect | A | B |\\n|---|---|---|\\n| What it does | ... | ... |\\n| When to use it | ... | ... |
              For a multi-step or multi-part (non-comparison) answer, use a bullet or numbered list
              instead, and bold key terms. A single short factual answer needs neither - use plain
              sentences when nothing is being compared or enumerated. "documentClaim" always stays
              plain prose (a normal sentence), never a table - it is a claim, not a comparison.
            - Respond with ONLY a JSON object of this exact shape, no markdown fences:
              {"documentClaim": string, "explanation": string, "insufficientContext": boolean}
            """;

    public static String groundedQaUserPrompt(String question, String context) {
        return """
                DOCUMENT EXCERPTS:
                %s

                QUESTION: %s
                """.formatted(context, question);
    }

    public static final String CLAIM_EXTRACTION_SYSTEM = """
            You extract a single, atomic, independently fact-checkable claim from the given text.
            - Preserve the claim EXACTLY as asserted, including when you believe it to be false,
              outdated, or scientifically incorrect. Your job here is only to state what was
              claimed - whether it is true is decided later, in a separate verification step.
              Never "correct" the claim, reverse it, or substitute the version you believe is
              accurate.
            - A claim is checkable/factual whenever it makes an assertion about the world that
              could in principle be verified against evidence, even one you believe to be false.
              Do not classify something as OPINION merely because it involves categorization,
              definitions, or terminology that could be debated (e.g. whether something counts as
              both a country and a continent is a checkable, definitional/geographic question,
              not an opinion) - reserve OPINION for statements expressing a subjective preference
              or value judgment with no objective right answer (e.g. "X is the best...").
            - If the text is a recommendation, prediction, or subjective interpretation rather
              than a checkable factual claim, set "checkable" to false and explain why in "reason".
            - Otherwise set "checkable" to true and produce a concise, self-contained claim in
              "claim" that could be verified against external evidence without needing the original
              document for context (resolve pronouns, add the general subject).
            - Classify the claim "type" as one of: FACTUAL, SCIENTIFIC, STATISTIC, HISTORICAL,
              OPINION, RECOMMENDATION, PREDICTION, INTERPRETATION.
            - Respond with ONLY a JSON object of this exact shape, no markdown fences:
              {"checkable": boolean, "claim": string, "type": string, "reason": string}
            """;

    public static String claimExtractionUserPrompt(String sourceText) {
        return "TEXT:\n" + sourceText;
    }

    public static final String CLAIM_DECOMPOSITION_SYSTEM = """
            You extract independently fact-checkable claims from the given text.
            - Preserve every material qualifier from the original text in the claim it belongs to
              - dates, quantities, magnitudes, locations, and other specifics that would be needed
              to actually verify the claim. Do not drop a qualifier just because the claim would
              still read fine without it; if the source specifies a detail, that detail must
              appear in the corresponding claim, not be silently lost.
            - Preserve claims exactly as asserted, including when you believe them to be false -
              never "correct" a claim to what you believe is accurate. Whether a claim is true is
              decided later, in a separate verification step.
            - If the text bundles multiple distinct assertions together (e.g. "X reduces disease Y
              by 60% and is the safest treatment" bundles an effectiveness claim, a magnitude claim,
              and a safety claim), split it into separate ATOMIC claims - each one checkable on its
              own, without needing the others to make sense. Resolve pronouns and add the general
              subject so each claim is self-contained.
            - If the text is a single simple claim already, return just that one claim.
            - When a sentence mixes a checkable factual assertion with subjective/opinion framing
              (e.g. "X happened, which everyone should be excited about"), extract the checkable
              portion as its own atomic claim and exclude only the subjective portion - do not
              discard the entire sentence just because part of it is subjective.
            - Skip anything that is entirely an opinion, recommendation, prediction, or subjective
              interpretation, with no independently checkable factual content at all - do not
              include it in the list.
            - For each claim, classify "type" as one of: FACTUAL, SCIENTIFIC, STATISTIC, HISTORICAL.
            - For each claim, set "timeSensitive" to true if the claim's truth could plausibly change
              over time (e.g. "is the safest treatment", "is the current record holder", statistics
              that get periodically updated) and false for claims that are permanently fixed once true
              (e.g. a historical event's date).
            - Respond with ONLY a JSON object of this exact shape, no markdown fences:
              {"claims": [{"claim": string, "type": string, "timeSensitive": boolean}]}
              If nothing in the text is checkable, respond with {"claims": []}.
            """;

    public static String claimDecompositionUserPrompt(String sourceText) {
        return "TEXT:\n" + sourceText;
    }

    public static final String SOURCE_BATCH_CLASSIFICATION_SYSTEM = """
            You compare a factual claim against several web sources and classify EACH source's
            stance toward the claim independently. This is one call classifying many sources at once
            (rather than one call per source) purely for speed - judge each source on its own merits.
            - For each source, "stance" must be one of: SUPPORTS, CONTRADICTS, MIXED, NOT_RELEVANT.
              NOT_RELEVANT means that source does not actually address the claim.
            - "reasoning" is one short sentence per source explaining why, citing what that source
              actually says.
            - Be conservative: only choose SUPPORTS or CONTRADICTS when a source clearly and directly
              addresses the claim. Prefer MIXED when a source presents nuance (e.g. correlation vs
              causation, conditional support, partial agreement).
            - Respond with ONLY a JSON object of this exact shape, no markdown fences, with exactly
              one result per source in the same order given:
              {"results": [{"index": number, "stance": string, "reasoning": string}]}
            """;

    public static String sourceBatchClassificationUserPrompt(String claim, List<String> titles, List<String> contents) {
        StringBuilder sb = new StringBuilder("CLAIM: ").append(claim).append("\n\nSOURCES:\n");
        for (int i = 0; i < titles.size(); i++) {
            sb.append("[%d] TITLE: %s\nCONTENT: %s\n\n".formatted(i, titles.get(i), contents.get(i)));
        }
        return sb.toString();
    }

    public static final String FACT_CHECK_SUMMARY_SYSTEM = """
            You write a short, neutral summary (3-5 sentences) of a fact-check, given a claim and a
            list of source stances (SUPPORTS/CONTRADICTS/MIXED/NOT_RELEVANT) with brief reasoning for
            each. Explain what the supporting and contradicting evidence say, note any important
            distinctions (e.g. correlation vs causation, association vs proof, risk reduction vs
            prevention), and do not state a numeric confidence yourself - that is computed separately.
            Respond with plain text only, no JSON, no markdown headers.
            """;

    public static String factCheckSummaryUserPrompt(String claim, String stanceSummary) {
        return """
                CLAIM: %s

                SOURCE STANCES:
                %s
                """.formatted(claim, stanceSummary);
    }

    /** Deliberately separate from FACT_CHECK_SUMMARY_SYSTEM: "Summarize" (from the PDF selection
     *  toolbar, or the plain-text summarize flow) is a direct summary of the selected content
     *  itself - no claim extraction, no web search, no verdict. Do not route this through the
     *  fact-check pipeline; that's a different, heavier feature with its own UI. */
    public static final String SUMMARIZE_SYSTEM = """
            You write a short, plain summary of the given text. Preserve its key facts and meaning
            - never add outside information, opinions, or commentary not present in the text itself.
            If the text is source code, summarize what the code does rather than restating it line
            by line. If the text is a vision model's description of an image (diagrams, charts,
            screenshots, etc. often read that way), summarize what the image actually shows, not
            the fact that it's a description.
            Keep it to 2-4 sentences for most text; a short Markdown bullet list is fine only if the
            text has several genuinely distinct points that don't read well as prose.
            Respond with plain text only (or plain Markdown bullets, if used) - no JSON, no headers,
            no preamble like "This text is about" or "In summary".
            """;

    public static String summarizeUserPrompt(String text) {
        return "TEXT TO SUMMARIZE:\n" + text;
    }

    /**
     * Drives one round of Quality mode's research loop (see DeepResearchService): given the question
     * and the answer drafted so far, decide whether anything is still missing and, if so, what to go
     * looking for next.
     */
    public static final String GAP_ANALYSIS_SYSTEM = """
            You are reviewing a draft answer to decide whether it still needs more evidence from the
            document, as one step of an iterative research process.
            - Set "complete" to true when the draft already answers the question as fully as the
              question actually asks. Do not demand more detail than was asked for; a question with
              a short, direct answer is complete once that answer is given.
            - Set "complete" to false ONLY when a specific, identifiable part of the question is
              genuinely unanswered or unsupported by the evidence so far.
            - When "complete" is false, "missingQuery" must be a short search phrase describing the
              missing information, phrased to match wording likely to appear in the document itself
              (not a question, and not a restatement of the original question).
            - When "complete" is true, "missingQuery" must be an empty string.
            - Respond with ONLY a JSON object of this exact shape, no markdown fences:
              {"complete": boolean, "missingQuery": string}
            """;

    public static String gapAnalysisUserPrompt(String question, String draftAnswer) {
        return """
                ORIGINAL QUESTION: %s

                DRAFT ANSWER SO FAR:
                %s
                """.formatted(question, draftAnswer);
    }
}
