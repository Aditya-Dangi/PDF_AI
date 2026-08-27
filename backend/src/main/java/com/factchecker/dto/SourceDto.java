package com.factchecker.dto;

public record SourceDto(
        String url,
        String title,
        String snippet,
        String stance,
        String authorityTier,
        String publishedDate
) {
}
