/**
 * Rough expected durations (seconds) per operation, used only to drive the loading progress bar's
 * fill rate - never shown as a promise. Retuned after moving Ollama to GPU (RTX 2060 Super):
 * generation throughput went from ~9 tokens/sec (CPU) to ~72 tokens/sec (GPU, ~8x), and a full
 * fact-check round trip that previously took ~80s+ now completes in ~1-2s in practice. These
 * numbers stay a bit more generous than that best case, since web search latency (SearXNG) doesn't
 * get faster from a GPU - and the bar eases off rather than stalling if a call runs longer anyway.
 */
export const TIME_ESTIMATES = {
  /** Uploading the file itself (not the async extraction/embedding that follows) - unaffected by GPU. */
  upload: 6,
  /** Asking a question: one retrieval pass + one grounded-generation LLM call. */
  ask: 5,
  /** Fact-checking a single already-extracted claim: web search + one batched classification call. */
  factCheck: 10,
  /** Breaking a statement into atomic claims and fully verifying each one found. */
  decompose: 15,
  /** Challenge mode: a wider counter-evidence search on top of a normal verification pass. */
  challenge: 12
} as const;

/** Audit mode scans every chunk for claims, then fully verifies each one found (capped) - scale
 *  the estimate with document length since that's the only signal available before it starts.
 *  Chunk decomposition and claim verification now also run concurrently (see ClaimService), on
 *  top of the GPU speedup, so this is well below the old CPU-era, fully-sequential estimate. */
export function estimateAuditSeconds(pageCount: number): number {
  return Math.max(20, Math.round(pageCount * 3));
}

/** Ingestion (parse -> OCR fallback -> chunk -> batch-embed) scales with page count; pageCount is
 *  0 until the backend finishes extracting text, so this falls back to a flat estimate until then.
 *  Measured ~44s end-to-end for a real 117-page/864-chunk document on GPU-batched embedding. */
export function estimateProcessingSeconds(pageCount: number): number {
  return pageCount > 0 ? Math.max(10, Math.round(pageCount * 0.5)) : 15;
}
