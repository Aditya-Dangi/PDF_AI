/**
 * Expected durations (seconds) per operation, used only to drive the loading progress bar's fill
 * rate - never shown as a promise.
 *
 * These are measured, not guessed. Values below come from timing the real endpoints against a
 * 300-page / 3600-chunk document on this hardware (RTX 2060 Super, llama3.1:8b):
 *
 *   fast ask     13.5s cold, then 2.8 / 2.6 / 2.4s warm
 *   quality ask  12.2 / 10.0 / 13.5s  (2-3 research rounds)
 *   fact-check   10.2 / 7.6 / 7.9s    (web search dominates, not the GPU)
 *   summarize     0.6 / 0.4 / 0.3s warm  (tiny input, short output)
 *
 * The first call after an idle period pays for loading the model into VRAM and is several times
 * slower than every call after it. Estimates sit above the warm case so the bar does not cap out
 * and stall on a cold start, but well below the cold case, since most calls in a session are warm.
 */
export const TIME_ESTIMATES = {
  /** Uploading the file itself (not the async extraction/embedding that follows) - unaffected by GPU. */
  upload: 6,
  /** Asking a question: one retrieval pass + one grounded-generation LLM call. */
  ask: 5,
  /**
   * Quality mode: several retrieval/answer rounds plus a gap-analysis call between each.
   * Measured 10-13.5s for the 2-3 rounds that are typical; the server-side ceiling is far higher
   * (see app.deep-research.time-budget-ms) but reaching it is the exception, not the norm.
   */
  askQuality: 14,
  /** Fact-checking a single already-extracted claim: web search + one batched classification call. */
  factCheck: 9,
  /** Summarizing a selection: one short LLM call over a short input - by far the cheapest operation. */
  summarize: 3,
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

/**
 * Ingestion (parse -> OCR fallback -> chunk -> batch-embed) scales with page count.
 *
 * Measured on this hardware, ingesting generated documents of realistic text density
 * (~34 lines and 12 chunks per page):
 *
 *    10 pages /  120 chunks ->  5s
 *    40 pages /  480 chunks -> 11s
 *   120 pages / 1440 chunks -> 21s
 *   300 pages / 3600 chunks -> 51s
 *
 * That is close to linear at ~0.16s per page over a ~3s fixed cost. The previous 0.5s per page was
 * roughly three times too slow, which made the bar crawl and badly overstate the wait on long
 * documents.
 *
 * pageCount is 0 until the backend finishes extracting text. DOC/DOCX uploads add a LibreOffice
 * conversion step *before* extraction starts, so it can stay 0 for a while - long enough that a
 * flat fallback undersells the wait and lets the bar sit at its cap. fileSizeBytes is known
 * instantly at upload time and stands in for that window until the real page count arrives.
 */
export function estimateProcessingSeconds(pageCount: number, fileSizeBytes?: number): number {
  if (pageCount > 0) return Math.max(6, Math.round(pageCount * 0.16 + 3));
  if (fileSizeBytes) return Math.max(20, Math.round((fileSizeBytes / (1024 * 1024)) * 12));
  return 20;
}
