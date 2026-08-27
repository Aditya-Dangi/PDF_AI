package com.factchecker.pdf;

/**
 * One line of text on a page, with its bounding box in page point space (top-left origin, y-down).
 * See RectDto.
 *
 * <p>{@code fontSize} and {@code bold} drive structure classification (see DocumentStructureService)
 * - headings are detected primarily by font size relative to the document's body size. OCR-derived
 * lines have no font metrics available, so they carry {@code fontSize == 0} and {@code bold == false}
 * and are treated as body text.
 */
public record ExtractedLine(
        int page,
        String text,
        double x,
        double y,
        double width,
        double height,
        boolean ocr,
        double fontSize,
        boolean bold
) {
}
