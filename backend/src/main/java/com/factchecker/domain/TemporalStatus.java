package com.factchecker.domain;

public enum TemporalStatus {
    /** The claim isn't time-sensitive - recency doesn't materially affect its validity. */
    NOT_TIME_SENSITIVE,
    /** Time-sensitive, and available evidence is recent/consistent enough to trust as current. */
    CURRENT,
    /** Older evidence supported the claim, but newer evidence points the other way. */
    HISTORICAL_OUTDATED,
    /** The claim is time-sensitive but no dated evidence was found to confirm current status. */
    TIME_SENSITIVE_UNVERIFIED
}
