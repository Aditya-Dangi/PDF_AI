package com.factchecker.rag;

/**
 * The outcome of a Quality-mode research run.
 *
 * @param result     the best answer produced. Never null - every termination path, including
 *                   failures, returns the best draft available rather than losing the request.
 * @param rounds     how many retrieval/answer rounds actually ran.
 * @param stopReason why the loop ended, surfaced so a partial answer can say so honestly.
 */
public record DeepResearchResult(
        RagResult result,
        int rounds,
        StopReason stopReason
) {
    public enum StopReason {
        /** Gap analysis reported the answer was complete. */
        COMPLETE,
        /** Hit the hard maximum number of rounds. */
        ROUND_CAP,
        /** Hit the global wall-clock budget for the whole request. */
        TIME_BUDGET,
        /** A round retrieved nothing that had not already been seen, so further rounds cannot help. */
        NO_NEW_EVIDENCE,
        /** Gap analysis failed or returned unusable output; treated as "stop here", never a retry. */
        GAP_ANALYSIS_UNAVAILABLE,
        /** Retrieval found nothing relevant enough to answer from. */
        INSUFFICIENT_CONTEXT
    }
}
