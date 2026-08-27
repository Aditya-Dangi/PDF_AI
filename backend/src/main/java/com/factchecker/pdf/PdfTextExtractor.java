package com.factchecker.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        /** PDFs have no reliable weight flag, so this reads the font's own name - the convention
         *  every real-world PDF follows (e.g. "ABCDEF+Helvetica-Bold", "Arial,BoldMT"). Falls back
         *  to false rather than guessing when the font or its name is unavailable. */
        private static boolean isBold(TextPosition tp) {
            if (tp.getFont() == null || tp.getFont().getName() == null) return false;
            String name = tp.getFont().getName().toLowerCase();
            return name.contains("bold") || name.contains("black") || name.contains("heavy");
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isBlank() || textPositions.isEmpty()) return;

            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            // Font size and weight are taken from the line's most common glyph rather than its
            // first: a heading that starts with a bullet, quote mark, or drop cap would otherwise
            // report that decoration's size instead of the heading's own.
            Map<Long, Integer> sizeCounts = new HashMap<>();
            int boldGlyphs = 0;
            int totalGlyphs = 0;

            for (TextPosition tp : textPositions) {
                double x = tp.getXDirAdj();
                double y = tp.getYDirAdj() - tp.getHeightDir();
                double w = tp.getWidthDirAdj();
                double h = tp.getHeightDir();

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);

                if (tp.getUnicode() == null || tp.getUnicode().isBlank()) continue;
                totalGlyphs++;
                // Rounded to 1dp so trivial sub-point rendering differences within one visual line
                // don't split into separate "sizes" and defeat the most-common vote.
                long sizeKey = Math.round(tp.getFontSizeInPt() * 10);
                sizeCounts.merge(sizeKey, 1, Integer::sum);
                if (isBold(tp)) boldGlyphs++;
            }

            double fontSize = sizeCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> e.getKey() / 10.0)
                    .orElse(0.0);

            lines.add(new ExtractedLine(
                    currentPageNumber,
                    text,
                    minX,
                    minY,
                    maxX - minX,
                    maxY - minY,
                    false,
                    fontSize,
                    totalGlyphs > 0 && boldGlyphs * 2 > totalGlyphs
            ));
        }
    }
}
