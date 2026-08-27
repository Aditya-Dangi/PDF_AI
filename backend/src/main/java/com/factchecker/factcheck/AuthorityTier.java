package com.factchecker.factcheck;

public enum AuthorityTier {
    /** Government, academic, or major scientific/medical bodies - e.g. .gov, .edu, WHO, NIH, peer-reviewed journals. */
    PRIMARY_AUTHORITY,
    /** Established, editorially-reviewed news/reference organizations. */
    ESTABLISHED,
    /** Useful for context but not treated as independently authoritative (e.g. Wikipedia, forums). */
    CONTEXT_ONLY,
    /** Unknown or low-authority sources (personal blogs, content farms, unverified sites). */
    UNKNOWN
}
