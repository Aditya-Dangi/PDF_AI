package com.factchecker.service;

import com.factchecker.common.JsonUtil;
import com.factchecker.domain.Chunk;
import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import com.factchecker.embedding.EmbeddingService;
import com.factchecker.pdf.ChunkBuilder;
import com.factchecker.pdf.ChunkCandidate;
import com.factchecker.pdf.DocumentConversionService;
import com.factchecker.pdf.ExtractedLine;
import com.factchecker.pdf.ExtractedPage;
import com.factchecker.pdf.OcrService;
import com.factchecker.pdf.PdfTextExtractor;
import com.factchecker.repository.ChunkRepository;
import com.factchecker.repository.DocumentRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full document ingestion pipeline (parse -> OCR fallback -> chunk -> embed -> persist)
 * asynchronously after upload, so the upload request returns immediately with status PROCESSING.
 */
@Service
public class PdfProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessingService.class);
    /** Chunks per embedding call - batches hundreds of chunks into a handful of Ollama requests
     *  instead of one per chunk, while keeping any single request body/latency bounded. */
    private static final int EMBEDDING_BATCH_SIZE = 32;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final PdfTextExtractor pdfTextExtractor;
    private final OcrService ocrService;
    private final ChunkBuilder chunkBuilder;
    private final EmbeddingService embeddingService;
    private final DocumentConversionService documentConversionService;
    private final JsonUtil jsonUtil;

    public PdfProcessingService(DocumentRepository documentRepository, ChunkRepository chunkRepository,
                                 PdfTextExtractor pdfTextExtractor, OcrService ocrService, ChunkBuilder chunkBuilder,
                                 EmbeddingService embeddingService, DocumentConversionService documentConversionService,
                                 JsonUtil jsonUtil) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.pdfTextExtractor = pdfTextExtractor;
        this.ocrService = ocrService;
        this.chunkBuilder = chunkBuilder;
        this.embeddingService = embeddingService;
        this.documentConversionService = documentConversionService;
        this.jsonUtil = jsonUtil;
    }

    @Async("documentProcessingExecutor")
    public void process(String documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.error("Document {} disappeared before processing could start", documentId);
            return;
        }

        try {
            String storagePath = document.getStoragePath();
            if (documentConversionService.needsConversion(storagePath)) {
                Path original = Path.of(storagePath);
                Path converted = documentConversionService.convertToPdf(original);
                Files.deleteIfExists(original);
                storagePath = converted.toAbsolutePath().toString();
                document.setStoragePath(storagePath);
                documentRepository.save(document);
            }

            List<ExtractedPage> pages = extractAllPages(storagePath);
            document.setPageCount(pages.size());
            // Persisted immediately (rather than only at the end) so the page count is visible to
            // the frontend - which uses it to estimate remaining processing time - while the
            // embedding step below, the slowest part of ingestion, is still running.
            documentRepository.save(document);

            List<ChunkCandidate> candidates = chunkBuilder.buildChunks(pages);
            if (candidates.isEmpty()) {
                document.setStatus(DocumentStatus.FAILED);
                document.setFailureReason("No extractable text was found in this document, even after OCR.");
                documentRepository.save(document);
                return;
            }

            for (int start = 0; start < candidates.size(); start += EMBEDDING_BATCH_SIZE) {
                List<ChunkCandidate> batch = candidates.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, candidates.size()));
                List<double[]> embeddings = embeddingService.embedBatch(batch.stream().map(ChunkCandidate::text).toList());

                for (int i = 0; i < batch.size(); i++) {
                    ChunkCandidate candidate = batch.get(i);
                    Chunk chunk = new Chunk();
                    chunk.setDocumentId(documentId);
                    chunk.setPage(candidate.page());
                    chunk.setChunkOrder(candidate.order());
                    chunk.setText(candidate.text());
                    chunk.setRectsJson(jsonUtil.toJson(candidate.rects()));
                    chunk.setEmbeddingJson(jsonUtil.toJson(embeddings.get(i)));
                    chunk.setCharCount(candidate.text().length());
                    chunk.setOcr(candidate.ocr());
                    chunkRepository.save(chunk);
                }
            }

            document.setStatus(DocumentStatus.READY);
            documentRepository.save(document);
            log.info("Document {} processed: {} pages, {} chunks", documentId, pages.size(), candidates.size());
        } catch (Throwable ex) {
            // Catches Throwable, not just Exception: this async job's whole point is to always
            // leave the document in a terminal state (READY or FAILED) rather than stuck in
            // PROCESSING forever. An uncaught Error here (e.g. a native-library failure) would
            // otherwise crash silently past this handler, orphaning the document indefinitely.
            log.error("Failed to process document {}: {}", documentId, ex.getMessage(), ex);
            document.setStatus(DocumentStatus.FAILED);
            document.setFailureReason(safeMessage(ex));
            documentRepository.save(document);
        }
    }

    private List<ExtractedPage> extractAllPages(String storagePath) throws Exception {
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

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) message = ex.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
