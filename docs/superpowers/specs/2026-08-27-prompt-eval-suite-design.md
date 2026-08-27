# Design: Prompt Evaluation Suite for Prompts.java

**Date:** 2026-08-27
**Status:** Approved, pending implementation plan

## Context

`backend/src/main/java/com/factchecker/llm/Prompts.java` holds 5 system prompts
(`GROUNDED_QA_SYSTEM`, `CLAIM_EXTRACTION_SYSTEM`, `CLAIM_DECOMPOSITION_SYSTEM`,
`SOURCE_BATCH_CLASSIFICATION_SYSTEM`, `FACT_CHECK_SUMMARY_SYSTEM`). Real usage tonight
surfaced two concrete `GROUNDED_QA_SYSTEM` quality bugs that were only found by manually
asking the app questions and reading the answers:

1. A table-of-contents line got embedded and retrieved as top evidence for "what is rxjs",
   producing a garbled `documentClaim` (fixed separately, in `ChunkBuilder`).
2. `documentClaim` dumped a raw, run-on quote instead of a clean sentence, and the
   explanation ignored the "in my project" part of a question, defaulting to generic
   textbook content instead of the project-specific excerpt it was given (fixed via a
   `Prompts.java` edit).

Both were found by accident, one conversation turn at a time. This spec is for a
repeatable way to catch this class of issue deliberately, by running many realistic
questions through the real system and having a second model judge the answers.

## Goals

- Catch prompt-quality regressions in all 5 prompts before they're found by accident.
- Exercise prompts through their **real callers** (not isolated prompt-string testing),
  so retrieval/pipeline issues get caught by the same suite, not just prompt wording.
- Produce a reviewable record of what passed/failed and why, for iterating on the prompts.

## Non-goals

- Model fine-tuning (a separate, much heavier ML project - explicitly ruled out).
- Continuous/CI integration - this is a manually-invoked, on-demand suite.
- Covering prompts other than the 5 already in `Prompts.java`.
- Guaranteeing deterministic pass/fail - LLM-as-judge is inherently probabilistic; the
  suite is a quality signal to guide iteration, not a hard gate.

## Architecture: 3 real end-to-end flows

Tracing the actual call graph shows the 5 prompts are only reachable through 3 real
entry points - testing through these (rather than calling `OllamaChatClient.generate()`
directly with hand-built inputs) means the suite also catches pipeline/retrieval issues,
not just prompt-wording issues.

| Flow | Real entry point | Prompts exercised |
|---|---|---|
| **A - Ask** | `RagService.answer()`, via a real uploaded/processed synthetic PDF | `GROUNDED_QA_SYSTEM` |
| **B - Fact-check** | `FactCheckService.factCheck(text)` (real SearXNG search included) | `CLAIM_EXTRACTION_SYSTEM`, `SOURCE_BATCH_CLASSIFICATION_SYSTEM`, `FACT_CHECK_SUMMARY_SYSTEM` |
| **C - Decompose** | `ClaimService.decompose(document, userId, request)` | `CLAIM_DECOMPOSITION_SYSTEM` (incidentally re-exercises B's downstream prompts per extracted claim - a bonus signal, not the target) |

**Target case count:** ~15 cases for Flow A (covers 1 prompt on its own, the highest-risk
one - it's the only one with a retrieval step, and where both real bugs tonight came
from), ~12 for Flow B, ~10 for Flow C - roughly 35-40 total, matching the "tens to
fifties" scope from the original ask.

**Constraint on Flow B:** it hits real, live web search. Test claims must be stable,
uncontroversial facts (e.g. "the Eiffel Tower is in Paris"), never time-sensitive claims
whose true answer could change between runs.

## Test case data model

One shape covers all 3 flows:

```java
record EvalCase(
    String id,              // short slug, e.g. "grounded-qa-toc-pollution"
    Flow flow,               // A, B, or C
    String input,             // the question / claim text / statement to decompose
    String expectation        // plain-language description of what a good answer must do
) {}
```

The judge is given `{flow, input, expectation, actual result}` - "actual result" is
flow-specific: `documentClaim` + `explanation` for A, `claim` + `verdict` + `summary` for
B, the list of extracted atomic claims for C.

## Synthetic test PDFs (Flow A only)

Generated with PDFBox (already a backend dependency - no new tooling, no external
scripts) directly in test setup code. 2-3 purpose-built documents, each targeting a
pattern that has actually caused a real bug or is a realistic risk:

1. A document with a table of contents (dot-leader lines) alongside real content.
2. A document with a heading glued directly to a code block, no separating punctuation.
3. A document with a first-person "here's how I used X in my project" narrative section
   alongside generic reference/theory content on the same topic.

Multiple questions get asked per document to reach the target case count without
needing many separate files.

## The judge

A second `OllamaChatClient` instance, constructed with its own `AppProperties` (a plain
POJO - no production code changes needed) pointed at **`llama3.1:8b`** instead of the
production `llama3.2` chat model - an independent, stronger local model, still fully
local/free. Requires a one-time `ollama pull llama3.1:8b` (fits comfortably in the RTX
2060 Super's 8GB VRAM).

Judge output contract:
```json
{"verdict": "PASS" | "FAIL", "reasoning": "one or two sentences"}
```

## Test runner

- 3 `@SpringBootTest` classes, one per flow (e.g. `GroundedQaEvalTest`,
  `FactCheckEvalTest`, `DecomposeEvalTest`), each `@Tag("eval")`.
- `pom.xml`'s Surefire config excludes the `eval` tag by default, so `./mvnw test` stays
  fast, deterministic, and unaffected.
- Run explicitly via `./mvnw test -Dgroups=eval` (or the IDE's tag-filtered run) when you
  want a quality check, e.g. after editing `Prompts.java`.

## Cleanup

Each test tracks the document/claim IDs it creates and deletes them in `@AfterEach`, so
eval runs never leave test data in the real dev database.

## Reporting

- **Console:** live pass/fail count per flow as the suite runs.
- **File:** `backend/eval-reports/<timestamp>.md` (gitignored) - every case's input,
  actual answer, and the judge's verdict + reasoning, for calm after-the-fact review.

## Known trade-offs

- LLM-as-judge (even a stronger model) can itself be wrong or inconsistent - failures are
  a strong signal to investigate, not an infallible verdict. This was a deliberate choice
  over deterministic rule-checks, trading some reliability for the ability to catch
  subtler, more semantic issues (like "ignored the project-specific part of the question").
- Flow B's dependence on live web search means occasional failures may reflect search
  result drift rather than a real prompt regression - worth a quick sanity check before
  treating a Flow B failure as a prompt bug.
- This is a real chunk of implementation work (3 Spring test classes, synthetic PDF
  generation, a judge client, a report writer) - expect it to take a full focused session,
  not a quick patch.
