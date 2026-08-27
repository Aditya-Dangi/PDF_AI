package com.factchecker.dto;

/** How much work an answer request should do. Defaults to FAST everywhere, so existing callers and
 *  behavior are unchanged unless a client explicitly opts into deep research. */
public enum AnswerMode {
    /** One retrieval, one answer pass - the original behavior. */
    FAST,
    /** Iterative gap-filling research; slower, and bounded by DeepResearchService's stop conditions. */
    QUALITY
}
