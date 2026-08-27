package com.factchecker.dto;

import java.util.List;

public record EvidenceDto(
        String chunkId,
        int page,
        List<RectDto> rects,
        String text,
        double similarity
) {
}
