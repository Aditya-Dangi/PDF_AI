package com.factchecker.eval;

/** Fixed diagnosis vocabulary for a failed case - for reporting, not for expanding the suite. */
public enum FailureCategory {
    GROUNDING,
    QUESTION_INTERPRETATION,
    DOCUMENT_CLAIM,
    RETRIEVAL,
    HALLUCINATION,
    EXTRACTION,
    SOURCE_CLASSIFICATION,
    SUMMARY,
    DECOMPOSITION,
    FORMAT,
    /** Judge output itself was missing/unparseable, or no category otherwise applies. Never used
     *  by the judge on purpose - a fallback so a bad judge response can't crash the suite. */
    NONE
}
