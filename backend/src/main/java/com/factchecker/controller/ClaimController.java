package com.factchecker.controller;

import com.factchecker.claim.ClaimService;
import com.factchecker.domain.Document;
import com.factchecker.dto.AuditResponse;
import com.factchecker.dto.ClaimDecomposeRequest;
import com.factchecker.dto.ClaimResponse;
import com.factchecker.security.AuthenticatedUser;
import com.factchecker.service.DocumentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/documents/{documentId}")
public class ClaimController {

    private final ClaimService claimService;
    private final DocumentService documentService;

    public ClaimController(ClaimService claimService, DocumentService documentService) {
        this.claimService = claimService;
        this.documentService = documentService;
    }

    @PostMapping("/claims/decompose")
    public List<ClaimResponse> decompose(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                          @RequestBody ClaimDecomposeRequest request) {
        Document document = documentService.get(user.id(), documentId);
        return claimService.decompose(document, user.id(), request);
    }

    @GetMapping("/claims")
    public List<ClaimResponse> listClaims(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId) {
        Document document = documentService.get(user.id(), documentId);
        return claimService.listClaims(document);
    }

    @PostMapping("/claims/{claimId}/challenge")
    public ClaimResponse challenge(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId,
                                    @PathVariable String claimId) {
        Document document = documentService.get(user.id(), documentId);
        return claimService.challenge(document, claimId);
    }

    @PostMapping("/audit")
    public AuditResponse startAudit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId) {
        Document document = documentService.get(user.id(), documentId);
        claimService.startAudit(document);
        return claimService.getAuditStatus(document);
    }

    @GetMapping("/audit")
    public AuditResponse auditStatus(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String documentId) {
        Document document = documentService.get(user.id(), documentId);
        return claimService.getAuditStatus(document);
    }
}
