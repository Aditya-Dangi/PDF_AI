package com.factchecker.pdf;

/** One line of text on a page, with its bounding box in page point space (top-left origin, y-down). See RectDto. */
public record ExtractedLine(
        int page,
        String text,
        double x,
        double y,
        double width,
        double height,
        boolean ocr
) {
}
