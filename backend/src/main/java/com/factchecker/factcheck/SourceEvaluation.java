package com.factchecker.factcheck;

public record SourceEvaluation(
        String url,
        String title,
        String snippet,
        Stance stance,
        AuthorityTier authorityTier,
        String publishedDate
) {
    public enum Stance {
        SUPPORTS, CONTRADICTS, MIXED, NOT_RELEVANT
    }
}
