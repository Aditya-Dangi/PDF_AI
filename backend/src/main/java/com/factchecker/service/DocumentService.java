package com.factchecker.service;

import com.factchecker.config.AppProperties;
import com.factchecker.domain.ChatMessage;
import com.factchecker.domain.Conversation;
import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import com.factchecker.exception.BadRequestException;
import com.factchecker.exception.ResourceNotFoundException;
import com.factchecker.repository.AnswerRepository;
import com.factchecker.repository.ChatMessageRepository;
import com.factchecker.repository.ChunkRepository;
import com.factchecker.repository.ClaimRepository;
import com.factchecker.repository.ConversationRepository;
import com.factchecker.repository.DocumentRepository;
import com.factchecker.repository.FactCheckRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final long MAX_FILE_SIZE_BYTES = 30L * 1024 * 1024;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AnswerRepository answerRepository;
    private final FactCheckRepository factCheckRepository;
    private final ClaimRepository claimRepository;
    private final PdfProcessingService pdfProcessingService;
    private final AppProperties appProperties;

    public DocumentService(DocumentRepository documentRepository, ChunkRepository chunkRepository,
                            ConversationRepository conversationRepository, ChatMessageRepository chatMessageRepository,
                            AnswerRepository answerRepository, FactCheckRepository factCheckRepository,
                            ClaimRepository claimRepository, PdfProcessingService pdfProcessingService,
                            AppProperties appProperties) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.answerRepository = answerRepository;
        this.factCheckRepository = factCheckRepository;
        this.claimRepository = claimRepository;
        this.pdfProcessingService = pdfProcessingService;
        this.appProperties = appProperties;
    }

    public Document upload(String userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("The uploaded file is empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BadRequestException("File exceeds the 30MB size limit.");
        }
        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        String extension = detectSupportedExtension(contentType, originalFilename);
        if (extension == null) {
            throw new BadRequestException("Only PDF, DOC, and DOCX files are supported.");
        }

        String documentId = UUID.randomUUID().toString();

        try {
            Path userDir = Path.of(appProperties.getUploadDir(), userId);
            Files.createDirectories(userDir);
            // Kept in its original format here - non-PDF files are converted to PDF by
            // PdfProcessingService before extraction (see DocumentConversionService).
            Path target = userDir.resolve(documentId + extension);
            file.transferTo(target);

            Document document = new Document();
            document.setId(documentId);
            document.setUserId(userId);
            document.setFilename(originalFilename != null ? originalFilename : "document.pdf");
            document.setStoragePath(target.toAbsolutePath().toString());
            document.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(document);

            pdfProcessingService.process(documentId);

            return document;
        } catch (IOException ex) {
            log.error("Failed to store uploaded file for user {}: {}", userId, ex.getMessage());
            throw new BadRequestException("Failed to store the uploaded file.");
        }
    }

    /** Returns the extension (with leading dot) to store the file under, or null if the file
     *  isn't a supported type. Checked by content-type first, falling back to the filename
     *  extension since browsers/clients don't always send an accurate content-type. */
    private String detectSupportedExtension(String contentType, String filename) {
        String lowerName = filename != null ? filename.toLowerCase() : "";
        if ("application/pdf".equals(contentType) || lowerName.endsWith(".pdf")) {
            return ".pdf";
        }
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)
                || lowerName.endsWith(".docx")) {
            return ".docx";
        }
        if ("application/msword".equals(contentType) || lowerName.endsWith(".doc")) {
            return ".doc";
        }
        return null;
    }

    public List<Document> list(String userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Document get(String userId, String documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found."));
    }

    @Transactional
    public void delete(String userId, String documentId) {
        Document document = get(userId, documentId);

        List<Conversation> conversations = conversationRepository.findByDocumentId(documentId);
        for (Conversation conversation : conversations) {
            List<ChatMessage> messages = chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
            for (ChatMessage message : messages) {
                answerRepository.findByMessageId(message.getId()).ifPresent(answerRepository::delete);
                factCheckRepository.findByMessageId(message.getId()).ifPresent(factCheckRepository::delete);
                chatMessageRepository.delete(message);
            }
            conversationRepository.delete(conversation);
        }

        chunkRepository.deleteByDocumentId(documentId);
        claimRepository.deleteByDocumentId(documentId);

        try {
            Files.deleteIfExists(Path.of(document.getStoragePath()));
        } catch (IOException ex) {
            log.warn("Failed to delete stored file for document {}: {}", documentId, ex.getMessage());
        }

        documentRepository.delete(document);
    }
}
