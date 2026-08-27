package com.factchecker.service;

import com.factchecker.common.JsonUtil;
import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStructure;
import com.factchecker.dto.DocumentBlockDto;
import com.factchecker.dto.StructureResponse;
import com.factchecker.exception.BadRequestException;
import com.factchecker.pdf.DocumentBlock;
import com.factchecker.pdf.DocumentStructureService;
import com.factchecker.pdf.ExtractedPage;
import com.factchecker.pdf.PageExtractor;
import com.factchecker.repository.DocumentStructureRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Serves the workspace Text pane: the document's Markdown rendering and typed block list,
 *  computed on first request and cached thereafter. */
@Service
public class DocumentTextService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextService.class);

    private final DocumentStructureRepository structureRepository;
    private final DocumentStructureService structureService;
    private final PageExtractor pageExtractor;
    private final JsonUtil jsonUtil;

    public DocumentTextService(DocumentStructureRepository structureRepository,
                                DocumentStructureService structureService,
                                PageExtractor pageExtractor, JsonUtil jsonUtil) {
        this.structureRepository = structureRepository;
        this.structureService = structureService;
        this.pageExtractor = pageExtractor;
        this.jsonUtil = jsonUtil;
    }

    @Transactional
    public StructureResponse getStructure(Document document) {
        return structureRepository.findByDocumentId(document.getId())
                .map(this::toResponse)
                .orElseGet(() -> computeAndCache(document));
    }

    private StructureResponse computeAndCache(Document document) {
        List<ExtractedPage> pages;
        try {
            pages = pageExtractor.extractAllPages(document.getStoragePath());
        } catch (Throwable ex) {
            // Throwable, matching PdfProcessingService: a native OCR failure surfaces as an Error,
            // and this must degrade to a clear message rather than a 500 that also takes down the
            // PDF and AI panes the user is still using.
            log.error("Failed to extract text structure for document {}: {}", document.getId(), ex.getMessage(), ex);
            throw new BadRequestException("Could not extract this document's text: " + ex.getMessage());
        }

        List<DocumentBlock> blocks = structureService.buildBlocks(pages);
        String markdown = structureService.toMarkdown(blocks);
        List<DocumentBlockDto> dtos = blocks.stream()
                .map(b -> new DocumentBlockDto(b.page(), b.type().name(), b.headingLevel(), b.text(), b.rects()))
                .toList();

        DocumentStructure cached = new DocumentStructure();
        cached.setDocumentId(document.getId());
        cached.setMarkdown(markdown);
        cached.setBlocksJson(jsonUtil.toJson(dtos));
        structureRepository.save(cached);

        return new StructureResponse(markdown, dtos);
    }

    private StructureResponse toResponse(DocumentStructure cached) {
        return new StructureResponse(
                cached.getMarkdown(),
                jsonUtil.fromJson(cached.getBlocksJson(), new TypeReference<List<DocumentBlockDto>>() {})
        );
    }
}
