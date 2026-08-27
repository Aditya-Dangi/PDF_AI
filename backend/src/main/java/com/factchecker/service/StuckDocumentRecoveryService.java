package com.factchecker.service;

import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import com.factchecker.repository.ChunkRepository;
import com.factchecker.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A document's ingestion pipeline runs on a background thread (see PdfProcessingService), so a
 * restart or crash mid-processing (deploy, OOM, etc.) leaves its row stuck in PROCESSING forever -
 * nothing was ever going to re-trigger it, and the frontend would just poll an ever-growing timer.
 * On startup, find any such orphaned documents, discard whatever partial chunks they wrote, and
 * re-run processing from the top - conversion/extraction/embedding are all safe to redo.
 */
@Component
public class StuckDocumentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(StuckDocumentRecoveryService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final PdfProcessingService pdfProcessingService;

    public StuckDocumentRecoveryService(DocumentRepository documentRepository, ChunkRepository chunkRepository,
                                         PdfProcessingService pdfProcessingService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.pdfProcessingService = pdfProcessingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckDocuments() {
        List<Document> stuck = documentRepository.findByStatus(DocumentStatus.PROCESSING);
        if (stuck.isEmpty()) return;

        log.warn("Found {} document(s) left in PROCESSING by an unclean shutdown - re-running ingestion", stuck.size());
        for (Document document : stuck) {
            chunkRepository.deleteByDocumentId(document.getId());
            pdfProcessingService.process(document.getId());
        }
    }
}
