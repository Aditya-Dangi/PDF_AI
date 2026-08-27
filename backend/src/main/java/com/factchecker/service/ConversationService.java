package com.factchecker.service;

import com.factchecker.common.JsonUtil;
import com.factchecker.domain.Answer;
import com.factchecker.domain.ChatMessage;
import com.factchecker.domain.Conversation;
import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import com.factchecker.domain.FactCheck;
import com.factchecker.domain.MessageRole;
import com.factchecker.dto.AnswerResponse;
import com.factchecker.dto.AskRequest;
import com.factchecker.dto.EvidenceDto;
import com.factchecker.dto.FactCheckRequest;
import com.factchecker.dto.FactCheckResponse;
import com.factchecker.dto.MessageResponse;
import com.factchecker.dto.RectDto;
import com.factchecker.dto.SourceDto;
import com.factchecker.exception.BadRequestException;
import com.factchecker.exception.ResourceNotFoundException;
import com.factchecker.factcheck.FactCheckResult;
import com.factchecker.factcheck.FactCheckService;
import com.factchecker.factcheck.SourceEvaluation;
import com.factchecker.rag.RagResult;
import com.factchecker.rag.RagService;
import com.factchecker.rag.RetrievedChunk;
import com.factchecker.repository.AnswerRepository;
import com.factchecker.repository.ChatMessageRepository;
import com.factchecker.repository.ConversationRepository;
import com.factchecker.repository.FactCheckRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AnswerRepository answerRepository;
    private final FactCheckRepository factCheckRepository;
    private final RagService ragService;
    private final FactCheckService factCheckService;
    private final JsonUtil jsonUtil;

    public ConversationService(ConversationRepository conversationRepository, ChatMessageRepository chatMessageRepository,
                                AnswerRepository answerRepository, FactCheckRepository factCheckRepository,
                                RagService ragService, FactCheckService factCheckService, JsonUtil jsonUtil) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.answerRepository = answerRepository;
        this.factCheckRepository = factCheckRepository;
        this.ragService = ragService;
        this.factCheckService = factCheckService;
        this.jsonUtil = jsonUtil;
    }

    @Transactional
    public AnswerResponse ask(Document document, String userId, AskRequest request) {
        requireReady(document);

        Conversation conversation = getOrCreateConversation(document.getId(), userId);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(request.question());
        chatMessageRepository.save(userMessage);

        RagResult result = ragService.answer(document.getId(), request.question());

        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversation.getId());
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setContent(result.explanation());
        chatMessageRepository.save(assistantMessage);

        List<EvidenceDto> evidenceDtos = toEvidenceDtos(result.evidence());

        Answer answer = new Answer();
        answer.setMessageId(assistantMessage.getId());
        answer.setDocumentClaim(result.documentClaim());
        answer.setExplanation(result.explanation());
        answer.setInsufficientContext(result.insufficientContext());
        answer.setRetrievalConfidence(result.retrievalConfidence());
        answer.setFidelityConfidence(result.fidelityConfidence());
        answer.setEvidenceJson(jsonUtil.toJson(evidenceDtos));
        answerRepository.save(answer);

        return new AnswerResponse(
                assistantMessage.getId(),
                request.question(),
                result.documentClaim(),
                result.explanation(),
                result.insufficientContext(),
                result.retrievalConfidence(),
                result.fidelityConfidence(),
                evidenceDtos
        );
    }

    @Transactional
    public FactCheckResponse factCheck(Document document, String userId, FactCheckRequest request) {
        boolean hasMessageId = request.messageId() != null && !request.messageId().isBlank();
        boolean hasClaimText = request.claimText() != null && !request.claimText().isBlank();

        if (hasMessageId == hasClaimText) {
            throw new BadRequestException("Provide exactly one of messageId or claimText.");
        }

        String targetMessageId;
        String sourceText;

        if (hasMessageId) {
            ChatMessage message = chatMessageRepository.findById(request.messageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Message not found."));
            Conversation owningConversation = conversationRepository.findById(message.getConversationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Message not found."));
            if (!owningConversation.getUserId().equals(userId) || !owningConversation.getDocumentId().equals(document.getId())) {
                throw new ResourceNotFoundException("Message not found.");
            }
            Answer answer = answerRepository.findByMessageId(message.getId())
                    .orElseThrow(() -> new BadRequestException("This message has no document answer to fact-check."));
            if (answer.isInsufficientContext()) {
                throw new BadRequestException("The document did not provide an answer for this message, so there is no claim to fact-check.");
            }
            targetMessageId = message.getId();
            sourceText = answer.getDocumentClaim();
        } else {
            Conversation conversation = getOrCreateConversation(document.getId(), userId);

            ChatMessage userMessage = new ChatMessage();
            userMessage.setConversationId(conversation.getId());
            userMessage.setRole(MessageRole.USER);
            userMessage.setContent("Fact-check: " + request.claimText());
            chatMessageRepository.save(userMessage);

            ChatMessage assistantMessage = new ChatMessage();
            assistantMessage.setConversationId(conversation.getId());
            assistantMessage.setRole(MessageRole.ASSISTANT);
            assistantMessage.setContent("");
            chatMessageRepository.save(assistantMessage);

            targetMessageId = assistantMessage.getId();
            sourceText = request.claimText();
        }

        FactCheckResult result = factCheckService.factCheck(sourceText);

        factCheckRepository.findByMessageId(targetMessageId).ifPresent(factCheckRepository::delete);

        List<SourceDto> sourceDtos = result.sources().stream()
                .map(s -> new SourceDto(s.url(), s.title(), s.snippet(), s.stance().name(), s.authorityTier().name(), s.publishedDate()))
                .toList();

        FactCheck factCheck = new FactCheck();
        factCheck.setMessageId(targetMessageId);
        factCheck.setClaimText(result.claimText());
        factCheck.setClaimType(result.claimType());
        factCheck.setCheckable(result.checkable());
        factCheck.setVerdict(result.verdict());
        factCheck.setWebConfidence(result.webConfidence());
        factCheck.setSummary(result.summary());
        factCheck.setSourcesJson(jsonUtil.toJson(sourceDtos));
        factCheckRepository.save(factCheck);

        if (!hasMessageId) {
            ChatMessage assistantMessage = chatMessageRepository.findById(targetMessageId).orElseThrow();
            assistantMessage.setContent(result.summary());
            chatMessageRepository.save(assistantMessage);
        }

        return new FactCheckResponse(
                targetMessageId,
                result.claimText(),
                result.claimType(),
                result.checkable(),
                result.verdict().name(),
                result.webConfidence(),
                result.summary(),
                sourceDtos
        );
    }

    public List<MessageResponse> history(Document document, String userId) {
        return conversationRepository.findByDocumentIdAndUserId(document.getId(), userId)
                .map(conversation -> chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                        .stream()
                        .map(this::toMessageResponse)
                        .toList())
                .orElse(List.of());
    }

    private MessageResponse toMessageResponse(ChatMessage message) {
        AnswerResponse answerResponse = answerRepository.findByMessageId(message.getId())
                .map(a -> new AnswerResponse(
                        message.getId(),
                        null,
                        a.getDocumentClaim(),
                        a.getExplanation(),
                        a.isInsufficientContext(),
                        a.getRetrievalConfidence(),
                        a.getFidelityConfidence(),
                        jsonUtil.fromJson(a.getEvidenceJson(), new TypeReference<List<EvidenceDto>>() {})
                ))
                .orElse(null);

        FactCheckResponse factCheckResponse = factCheckRepository.findByMessageId(message.getId())
                .map(f -> new FactCheckResponse(
                        message.getId(),
                        f.getClaimText(),
                        f.getClaimType(),
                        f.isCheckable(),
                        f.getVerdict().name(),
                        f.getWebConfidence(),
                        f.getSummary(),
                        jsonUtil.fromJson(f.getSourcesJson(), new TypeReference<List<SourceDto>>() {})
                ))
                .orElse(null);

        return new MessageResponse(
                message.getId(),
                message.getRole().name(),
                message.getContent(),
                message.getCreatedAt(),
                answerResponse,
                factCheckResponse
        );
    }

    private List<EvidenceDto> toEvidenceDtos(List<RetrievedChunk> evidence) {
        return evidence.stream()
                .map(rc -> new EvidenceDto(
                        rc.chunk().getId(),
                        rc.chunk().getPage(),
                        jsonUtil.fromJson(rc.chunk().getRectsJson(), new TypeReference<List<RectDto>>() {}),
                        rc.chunk().getText(),
                        rc.similarity()
                ))
                .toList();
    }

    private Conversation getOrCreateConversation(String documentId, String userId) {
        return conversationRepository.findByDocumentIdAndUserId(documentId, userId)
                .orElseGet(() -> {
                    Conversation conversation = new Conversation();
                    conversation.setDocumentId(documentId);
                    conversation.setUserId(userId);
                    return conversationRepository.save(conversation);
                });
    }

    private void requireReady(Document document) {
        if (document.getStatus() != DocumentStatus.READY) {
            throw new BadRequestException("Document is not ready yet (status: " + document.getStatus() + ").");
        }
    }
}
