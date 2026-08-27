# Design: Document Workspace v2 (Text Pane + Fast/Quality Modes)

**Date:** 2026-08-28
**Status:** Approved, pending implementation plan

## Context

Competitor review surfaced a broad product vision spanning five independent subsystems:

| # | Subsystem | Scope |
|---|-----------|-------|
| **A** | Document workspace v2 | Markdown/JSON extraction views, two-pane layout, Fast vs Quality modes |
| **B** | Public marketing site | Landing, pricing, footer, legal |
| **C** | Social proof | Reviews, ratings, comments, "wall of love" |
| **D** | Content engine | Agent that turns AI/PDF news into blog posts |
| **E** | AI Research | Topic search returning PDF/Word documents |

These do not share code, data, or release cycles, and were explicitly decomposed rather
than specced together. **This document covers subsystem A only.** B–E each get their own
spec.

Two decisions recorded here for later specs:

- **E is blocked on a source-scope decision.** Serving copyrighted books is not viable.
  Legitimate variants (open-access papers, government/standards documents, public-domain
  works, or user-published documents) must be chosen before E can be designed.
- **D should route every generated post through this product's own fact-check pipeline**
  and publish with evidence and source attribution. A fact-checking product publishing
  unverified AI content undermines its own premise; publishing verified content dogfoods
  the core engine.

## Goals

- Expose the document's extracted content as readable Markdown and machine-readable JSON,
  matching the developer-facing angle competitors lead with.
- Add a Quality answer mode that trades time for depth, distinct from today's single-pass
  behavior.
- Keep today's Fast path and all existing behavior unchanged by default.

## Non-goals

- A real ML document-layout model (LayoutLM-style) with genuine confidence scores. This
  is a research-scale task that would consume the entire phase; heuristics cover the need.
- Fabricated confidence percentages. Block types are labeled only where the heuristic is
  genuinely reliable; no score is invented to look sophisticated.
- A separate "Blocks" tab. The JSON tab already carries the structured data.
- A larger chat model for Quality mode. `llama3.1:8b` is already near the practical limit
  of an 8GB RTX 2060 Super, so depth comes from additional passes, not a bigger model.
- Subsystems B–E.

## Layout

The PDF stays permanently in the left pane. The right pane switches between **AI** and
**Text**; Text further switches between **Markdown** and **JSON**.

```
┌─ PDF ──────────┐┌─ AI │ Text ─────┐
│                ││                 │
│   [rendered]   ││   Markdown/JSON │
│                ││   or AI chat    │
└────────────────┘└─────────────────┘
      50%                 50%
```

Rejected: a third column (each pane drops to ~33%, and the PDF already scrolls
horizontally at 50%).

## Part 1: Text pane

### Extraction

`ExtractedLine` gains font size and bold flag. PDFBox exposes both via `TextPosition`;
they are simply not captured today. OCR-derived lines carry no font metrics and are
treated as body text by the classifier.

### Structure classification

A new service converts extracted pages into typed blocks. Block types: `HEADING`
(with level), `PARAGRAPH`, `LIST_ITEM`, `HEADER_FOOTER`.

Heuristics:

- **Body size** = the most common font size across the document.
- **Headings** = lines whose font size exceeds body size by more than 15%. Heading level
  is assigned by descending rank of the distinct larger sizes.
- **List items** = lines beginning with a bullet glyph or an ordered-list marker.
- **Headers/footers** = lines whose *normalized text* repeats at *approximately* the same
  y-position across at least 30% of pages. Matching on position alone would over-remove
  legitimate repeated content (recurring section labels, repeated field names in forms),
  so both text and position must agree. Normalization must tolerate the parts that
  legitimately vary between pages — most importantly page numbers, so that `Page 9` and
  `Page 10` are recognized as the same recurring footer. These blocks are excluded from
  the Markdown rendering.
- **Everything else** = `PARAGRAPH`, with consecutive lines merged.

Header/footer removal is the same class of problem as the table-of-contents pollution
found earlier in `ChunkBuilder`, and is expected to improve Markdown readability
substantially on real documents.

### Storage and serving

Structure is computed on first request and cached, keyed by document. Computing at
ingestion time was rejected: on-demand caching requires no migration or backfill for
existing documents and does not slow ingestion.

A single endpoint returns both the Markdown rendering and the block list, so switching
between the Markdown and JSON tabs costs no additional round trip.

### UI behavior

- Markdown renders through the existing Markdown pipe and prose styles.
- JSON is displayed as formatted, readable text.
- Both offer copy and download.
- Selecting a block highlights its rectangles on the PDF, reusing the existing
  target-page/target-rects wiring built for evidence highlighting.

## Part 2: Fast vs Quality modes

The ask request gains a mode with two values, defaulting to **Fast** so current behavior
and all existing tests are unaffected.

- **Fast** — today's behavior: single retrieval, single answer pass.
- **Quality** — an iterative gap-filling research loop.

### Quality loop behavior

Each round retrieves evidence, drafts an answer from all evidence accumulated so far, then
asks whether the answer is complete and, if not, what is still missing. The next round
retrieves against that gap.

Evidence accumulates across rounds; the answer is always drafted against everything
gathered so far, not just the current round.

### Stop conditions

The loop terminates on **any** of:

1. **Round cap** — a hard maximum of 3 rounds.
2. **Time budget** — 180 seconds. **This budget is global across the entire research
   request, not per round**; it is checked before starting each round.
3. **No new evidence** — a round retrieves only chunks already seen. This is the most
   important guardrail because it is deterministic: it does not depend on an 8B model
   correctly reporting that it is finished, and it means a document that has already given
   everything it has cannot cause the loop to spin.
4. **Gap analysis reports complete** — the model states nothing is missing.

Additionally, **malformed or failed gap analysis terminates the loop gracefully and
returns the best draft produced so far.** An unparseable or errored gap step is treated as
"stop here," never as a reason to retry indefinitely and never as a request failure.

More generally: every termination path, including hitting the round cap or the time
budget, returns the best draft available with an indication of why it stopped. Quality
mode never fails the request outright.

### UI behavior

- A Fast/Quality toggle in the ask input.
- Per-round progress while researching, rather than a single opaque spinner — a
  multi-minute wait must be legible.
- The answer indicates that deep research ran, how many rounds it took, and how long.

## Error handling

- Structure extraction failure degrades to a clear message in the Text tab; the PDF and AI
  panes are unaffected.
- Any Quality round failing returns the best draft so far rather than losing the request.
- Time budget exhaustion returns a partial answer, labeled as stopped early.

## Testing

- **Unit, no LLM** — the structure classifier against synthetic page fixtures: headings by
  font size, list markers, and repeated headers that must be removed only when both text
  and position match.
- **Unit, mocked** — the Quality loop's termination, asserting each stop condition
  independently: round cap, global time budget, no-new-evidence, gap-reports-complete, and
  malformed gap analysis. This exists specifically to hold the line on the termination risk
  identified during design.
- **Integration** — access control on the structure endpoint, matching the existing
  document access-control test pattern.
- **Eval** — Fast vs Quality comparison cases on multi-part questions, added to the
  existing prompt eval suite.
