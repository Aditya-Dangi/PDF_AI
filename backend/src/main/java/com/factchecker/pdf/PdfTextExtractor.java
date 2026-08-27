package com.factchecker.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts per-line text with bounding boxes (page point space, top-left origin, y-down) from a
 * PDF's embedded text layer using PDFBox. Pages with little or no extractable text (scanned/image
 * pages) are flagged via {@link #hasMeaningfulText} so the caller can fall back to OCR.
 */
@Component
public class PdfTextExtractor {

    private static final int MIN_CHARS_FOR_TEXT_LAYER = 10;

    public ExtractedPage extractPage(PDDocument document, int pageIndexZeroBased) throws IOException {
        PDPage page = document.getPage(pageIndexZeroBased);
        PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        int rotation = page.getRotation();
        double pageWidth = box.getWidth();
        double pageHeight = box.getHeight();
        if (rotation == 90 || rotation == 270) {
            double tmp = pageWidth;
            pageWidth = pageHeight;
            pageHeight = tmp;
        }

        LineCollectingStripper stripper = new LineCollectingStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndexZeroBased + 1);
        stripper.setEndPage(pageIndexZeroBased + 1);
        stripper.currentPageNumber = pageIndexZeroBased + 1;
        // Triggers processing without needing the output text (we only care about the captured lines).
        stripper.getText(document);

        return new ExtractedPage(pageIndexZeroBased + 1, pageWidth, pageHeight, stripper.lines);
    }

    public boolean hasMeaningfulText(ExtractedPage page) {
        int total = 0;
        for (ExtractedLine line : page.lines()) {
            total += line.text().trim().length();
            if (total >= MIN_CHARS_FOR_TEXT_LAYER) return true;
        }
        return false;
    }

    private static class LineCollectingStripper extends PDFTextStripper {
        final List<ExtractedLine> lines = new ArrayList<>();
        int currentPageNumber = 1;

        LineCollectingStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isBlank() || textPositions.isEmpty()) return;

            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;

            for (TextPosition tp : textPositions) {
                double x = tp.getXDirAdj();
                double y = tp.getYDirAdj() - tp.getHeightDir();
                double w = tp.getWidthDirAdj();
                double h = tp.getHeightDir();

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);
            }

            lines.add(new ExtractedLine(
                    currentPageNumber,
                    text,
                    minX,
                    minY,
                    maxX - minX,
                    maxY - minY,
                    false
            ));
        }
    }
}
