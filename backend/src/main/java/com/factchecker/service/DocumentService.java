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
        boolean looksLikePdf = "application/pdf".equals(contentType)
                || (originalFilename != null && originalFilename.toLowerCase().endsWith(".pdf"));
        if (!looksLikePdf) {
            throw new BadRequestException("Only PDF files are supported.");
        }

        String documentId = UUID.randomUUID().toString();

        try {
            Path userDir = Path.of(appProperties.getUploadDir(), userId);
            Files.createDirectories(userDir);
            Path target = userDir.resolve(documentId + ".pdf");
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
