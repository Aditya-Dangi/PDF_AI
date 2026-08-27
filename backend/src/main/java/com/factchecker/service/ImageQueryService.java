package com.factchecker.service;

import com.factchecker.exception.BadRequestException;
import com.factchecker.llm.OllamaVisionClient;
import com.factchecker.pdf.OcrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves a user-selected image region (from the PDF viewer's selection toolbar, for a diagram or
 * screenshot the text layer can't cover) into plain text that the existing ask/fact-check pipelines
 * can consume exactly like a typed question or a text selection - see ConversationController's
 * ask-image/fact-check-image endpoints. Tries OCR first (fast, reuses the OCR engine already used
 * for scanned pages); only calls the heavier vision model when OCR finds too little to work with.
 */
@Service
public class ImageQueryService {

    private static final Logger log = LoggerFactory.getLogger(ImageQueryService.class);
    /** Below this many OCR'd characters, the region is assumed to be a real diagram/chart rather
     *  than text OCR just did a poor job on - not worth treating a couple of stray recognized
     *  characters as the actual content of the selection. */
    private static final int MIN_OCR_CHARS = 15;

    private final OcrService ocrService;
    private final OllamaVisionClient visionClient;

    public ImageQueryService(OcrService ocrService, OllamaVisionClient visionClient) {
        this.ocrService = ocrService;
        this.visionClient = visionClient;
    }

    public String resolveQueryText(byte[] imageBytes) {
        String ocrText = ocrService.extractPlainText(imageBytes);
        if (ocrText.length() >= MIN_OCR_CHARS) {
            return ocrText;
        }

        if (!visionClient.isConfigured()) {
            throw new BadRequestException(ocrText.isEmpty()
                    ? "No readable text was found in the selected region, and no vision model is configured to describe it."
                    : "Not enough readable text was found in the selected region, and no vision model is configured to describe it.");
        }

        log.info("OCR found too little text in a selected image region ({} chars) - falling back to the vision model", ocrText.length());
        String description = visionClient.describe(imageBytes);
        if (description.isBlank()) {
            throw new BadRequestException("Could not extract any readable content from the selected image region.");
        }
        return description;
    }
}
