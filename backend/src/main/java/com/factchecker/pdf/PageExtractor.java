package com.factchecker.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a stored PDF and returns every page's extracted lines, falling back to OCR for pages whose
 * embedded text layer is empty or unusable (scanned pages).
 *
 * <p>Extracted here rather than left inside PdfProcessingService because two very different callers
 * need exactly this step: the async ingestion pipeline, and the on-demand structure/Markdown view.
 * Keeping one implementation means the Text pane can never disagree with what was indexed.
 */
@Component
public class PageExtractor {

    private final PdfTextExtractor pdfTextExtractor;
    private final OcrService ocrService;

    public PageExtractor(PdfTextExtractor pdfTextExtractor, OcrService ocrService) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.ocrService = ocrService;
    }

    public List<ExtractedPage> extractAllPages(String storagePath) throws Exception {
        List<ExtractedPage> pages = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(new File(storagePath))) {
            int pageCount = document.getNumberOfPages();

            for (int i = 0; i < pageCount; i++) {
                ExtractedPage page = pdfTextExtractor.extractPage(document, i);

                if (!pdfTextExtractor.hasMeaningfulText(page)) {
                    List<ExtractedLine> ocrLines = ocrService.ocrPage(document, i, i + 1);
                    page = new ExtractedPage(page.pageNumber(), page.width(), page.height(), ocrLines);
                }

                pages.add(page);
            }
        }

        return pages;
    }
}
