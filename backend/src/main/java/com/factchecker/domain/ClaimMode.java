package com.factchecker.domain;

public enum ClaimMode {
    /** Balanced verification: search for both support and counter-evidence, let the data decide. */
    NORMAL,
    /** User explicitly asked "try to disprove this" - counter-evidence search is emphasized and
     *  surfaced first, though the underlying verdict math stays the same honest formula. */
    CHALLENGE
}
