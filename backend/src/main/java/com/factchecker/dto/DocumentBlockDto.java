package com.factchecker.dto;

import java.util.List;

/** One structural block of a document, as returned by the /structure endpoint. */
public record DocumentBlockDto(
        int page,
        String type,
        int headingLevel,
        String text,
        List<RectDto> rects
) {
}
