package com.factchecker.controller;

import com.factchecker.domain.Document;
import com.factchecker.dto.AnswerResponse;
import com.factchecker.dto.AskRequest;
import com.factchecker.dto.FactCheckRequest;
import com.factchecker.dto.FactCheckResponse;
import com.factchecker.dto.MessageResponse;
import com.factchecker.security.AuthenticatedUser;
import com.factchecker.service.ConversationService;
import com.factchecker.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/documents/{documentId}")
public class ConversationController {

    private final ConversationService conversationService;
    private final DocumentService documentService;

    public ConversationController(ConversationService conversationService, DocumentService documentService) {
        this.conversationService = conversationService;
        this.documentService = documentService;
    }

    @PostMapping("/ask")
    public AnswerResponse ask(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                               @Valid @RequestBody AskRequest request) {
        Document document = documentService.get(user.id(), documentId);
        return conversationService.ask(document, user.id(), request);
    }

    @PostMapping("/fact-check")
    public FactCheckResponse factCheck(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                        @RequestBody FactCheckRequest request) {
        Document document = documentService.get(user.id(), documentId);
        return conversationService.factCheck(document, user.id(), request);
    }

    @GetMapping("/messages")
    public List<MessageResponse> messages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId) {
        Document document = documentService.get(user.id(), documentId);
        return conversationService.history(document, user.id());
    }
}
