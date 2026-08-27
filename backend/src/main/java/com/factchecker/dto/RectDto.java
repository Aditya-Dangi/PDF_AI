package com.factchecker.dto;

/**
 * A highlight rectangle in page point space (1/72 inch, same scale as the PDF media box), with the
 * origin at the page's TOP-LEFT corner and y increasing downward. This matches PDFBox's
 * TextPosition getXDirAdj()/getYDirAdj() output directly, and also matches how pdf.js/CSS render
 * pages on the frontend, so no axis flip is needed in either direction - only a uniform scale by
 * (renderedPixelWidth / pageWidthPoints).
 */
public record RectDto(double x, double y, double width, double height) {
}
