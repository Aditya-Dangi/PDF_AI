package com.factchecker.controller;

import com.factchecker.domain.Document;
import com.factchecker.dto.AnswerResponse;
import com.factchecker.dto.AskRequest;
import com.factchecker.dto.FactCheckRequest;
import com.factchecker.dto.FactCheckResponse;
import com.factchecker.dto.MessageResponse;
import com.factchecker.dto.SummarizeRequest;
import com.factchecker.dto.SummaryResponse;
import com.factchecker.security.AuthenticatedUser;
import com.factchecker.service.ConversationService;
import com.factchecker.service.DocumentService;
import com.factchecker.service.ImageQueryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/documents/{documentId}")
public class ConversationController {

    private final ConversationService conversationService;
    private final DocumentService documentService;
    private final ImageQueryService imageQueryService;

    public ConversationController(ConversationService conversationService, DocumentService documentService,
                                   ImageQueryService imageQueryService) {
        this.conversationService = conversationService;
        this.documentService = documentService;
        this.imageQueryService = imageQueryService;
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

    /** "Explain" from the PDF selection toolbar, for a dragged image region rather than selectable
     *  text (a diagram, chart, or screenshot the text layer doesn't cover) - resolves the image to
     *  text (OCR, or a vision-model description as a fallback) then delegates into the exact same
     *  grounded-QA flow as a typed question. */
    @PostMapping(value = "/ask-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnswerResponse askImage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                    @RequestParam("image") MultipartFile image) throws IOException {
        Document document = documentService.get(user.id(), documentId);
        String queryText = imageQueryService.resolveQueryText(image.getBytes());
        return conversationService.ask(document, user.id(), new AskRequest(queryText));
    }

    /** "Summarize" from the PDF selection toolbar, for a dragged image region - resolves the image
     *  to text the same way as askImage, then delegates into the exact same web fact-check flow as
     *  a typed claim. */
    @PostMapping(value = "/fact-check-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FactCheckResponse factCheckImage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                             @RequestParam("image") MultipartFile image) throws IOException {
        Document document = documentService.get(user.id(), documentId);
        String queryText = imageQueryService.resolveQueryText(image.getBytes());
        return conversationService.factCheck(document, user.id(), new FactCheckRequest(null, queryText));
    }

    /** "Summarize" from the PDF selection toolbar (or typed directly), for selectable text - a
     *  plain summary of the selection itself, deliberately NOT routed through the fact-check
     *  pipeline (no claim extraction, no web search, no verdict - see SummarizationService). */
    @PostMapping("/summarize")
    public SummaryResponse summarize(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                      @Valid @RequestBody SummarizeRequest request) {
        Document document = documentService.get(user.id(), documentId);
        return conversationService.summarize(document, user.id(), request.text());
    }

    /** Same as summarize(), for a dragged image region - resolves the image to text the same way
     *  as askImage/factCheckImage, then delegates into the same plain-summary flow. */
    @PostMapping(value = "/summarize-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SummaryResponse summarizeImage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                           @RequestParam("image") MultipartFile image) throws IOException {
        Document document = documentService.get(user.id(), documentId);
        String text = imageQueryService.resolveQueryText(image.getBytes());
        return conversationService.summarize(document, user.id(), text);
    }

    @GetMapping("/messages")
    public List<MessageResponse> messages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId) {
        Document document = documentService.get(user.id(), documentId);
        return conversationService.history(document, user.id());
    }

    /** Deletes one question+answer exchange (see ConversationService.deleteMessage - messageId is
     *  the ASSISTANT message's id, as returned in AnswerResponse/FactCheckResponse/SummaryResponse). */
    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                               @PathVariable String messageId) {
        Document document = documentService.get(user.id(), documentId);
        conversationService.deleteMessage(document, user.id(), messageId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/messages")
    public ResponseEntity<Void> clearMessages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId) {
        Document document = documentService.get(user.id(), documentId);
        conversationService.clearMessages(document, user.id());
        return ResponseEntity.noContent().build();
    }
}
