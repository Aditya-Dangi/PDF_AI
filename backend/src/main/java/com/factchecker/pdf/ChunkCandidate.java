package com.factchecker.pdf;

import com.factchecker.dto.RectDto;

import java.util.List;

public record ChunkCandidate(
        int page,
        int order,
        String text,
        List<RectDto> rects,
        boolean ocr
) {
}
