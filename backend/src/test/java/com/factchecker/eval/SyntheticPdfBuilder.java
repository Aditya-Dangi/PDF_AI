package com.factchecker.eval;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Builds small synthetic PDFs for Flow A test cases, using the PDFBox dependency already in the
 * project. Deliberately controls line spacing so the real ChunkBuilder's paragraph-gap heuristic
 * behaves as intended: normal single-line spacing keeps lines in the same chunk (used to reproduce
 * "heading glued directly to code"), while an extra gap forces a new paragraph/chunk.
 */
public final class SyntheticPdfBuilder {

    private static final float FONT_SIZE = 11f;
    private static final float LEADING = 14f;
    private static final float PARAGRAPH_GAP = 26f;
    private static final float MARGIN = 50f;

    private SyntheticPdfBuilder() {
    }

    /** A line of page text. paragraphBreakBefore=true inserts an extra gap before it (a new
     *  paragraph/chunk); false keeps it tight against the previous line (same chunk). */
    public record Line(String text, boolean paragraphBreakBefore) {
    }

    public static Line line(String text) {
        return new Line(text, false);
    }

    /** Starts a new paragraph - use this for a normal section break. */
    public static Line section(String text) {
        return new Line(text, true);
    }

    public static byte[] build(List<List<Line>> pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            for (List<Line> pageLines : pages) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(font, FONT_SIZE);
                    cs.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);

                    boolean first = true;
                    for (Line pageLine : pageLines) {
                        if (!first) {
                            float gap = pageLine.paragraphBreakBefore() ? LEADING + PARAGRAPH_GAP : LEADING;
                            cs.newLineAtOffset(0, -gap);
                        }
                        cs.showText(pageLine.text());
                        first = false;
                    }
                    cs.endText();
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
