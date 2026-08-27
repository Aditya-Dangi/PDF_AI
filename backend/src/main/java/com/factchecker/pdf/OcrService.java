package com.factchecker.pdf;

import com.factchecker.config.AppProperties;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR fallback for scanned/image-based PDF pages. Rasterizes the page (pure-Java via PDFBox, no
 * native canvas dependency) and runs Tesseract via Tess4J to recover text with line-level bounding
 * boxes. Requires a local Tesseract installation + tessdata (see app.ocr.tessdata-path); if OCR is
 * unavailable or fails, this degrades gracefully by returning no lines for that page rather than
 * failing the whole document.
 */
@Component
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final float RENDER_DPI = 200f;
    private static final double POINTS_PER_PIXEL = 72.0 / RENDER_DPI;

    private final AppProperties appProperties;

    public OcrService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public List<ExtractedLine> ocrPage(PDDocument document, int pageIndexZeroBased, int pageNumberOneBased) {
        List<ExtractedLine> lines = new ArrayList<>();
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageIndexZeroBased, RENDER_DPI);

            Tesseract tesseract = new Tesseract();
            String tessdataPath = appProperties.getOcr().getTessdataPath();
            if (tessdataPath != null && !tessdataPath.isBlank()) {
                tesseract.setDatapath(tessdataPath);
            }

            List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE);
            for (Word word : words) {
                String text = word.getText() == null ? "" : word.getText().trim();
                if (text.isEmpty()) continue;

                Rectangle box = word.getBoundingBox();
                lines.add(new ExtractedLine(
                        pageNumberOneBased,
                        text,
                        box.x * POINTS_PER_PIXEL,
                        box.y * POINTS_PER_PIXEL,
                        box.width * POINTS_PER_PIXEL,
                        box.height * POINTS_PER_PIXEL,
                        true
                ));
            }
        } catch (Throwable ex) {
            // Deliberately catches Throwable, not just Exception: a missing/broken native Tesseract
            // install surfaces as an UnsatisfiedLinkError, which is an Error, not an Exception - a
            // narrower catch here would let it slip past silently and crash the whole document's
            // async processing job instead of degrading gracefully as this method promises.
            log.warn("OCR failed for page {} - continuing without OCR text for this page. Cause: {}",
                    pageNumberOneBased, ex.getMessage());
        }
        return lines;
    }
}
