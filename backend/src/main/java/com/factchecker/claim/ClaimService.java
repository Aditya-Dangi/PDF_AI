package com.factchecker.claim;

import com.factchecker.common.JsonUtil;
import com.factchecker.domain.Answer;
import com.factchecker.domain.AuditStatus;
import com.factchecker.domain.ChatMessage;
import com.factchecker.domain.Claim;
import com.factchecker.domain.ClaimMode;
import com.factchecker.domain.Conversation;
import com.factchecker.domain.Document;
import com.factchecker.dto.AuditResponse;
import com.factchecker.dto.ClaimDecomposeRequest;
import com.factchecker.dto.ClaimResponse;
import com.factchecker.dto.EvidenceDto;
import com.factchecker.dto.RectDto;
import com.factchecker.dto.SourceDto;
import com.factchecker.exception.BadRequestException;
import com.factchecker.exception.ResourceNotFoundException;
import com.factchecker.factcheck.SourceEvaluation;
import com.factchecker.rag.RetrievedChunk;
import com.factchecker.repository.AnswerRepository;
import com.factchecker.repository.ChatMessageRepository;
import com.factchecker.repository.ChunkRepository;
import com.factchecker.repository.ClaimRepository;
import com.factchecker.repository.ConversationRepository;
import com.factchecker.repository.DocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Persistence + orchestration for the claim-intelligence features (decomposition, document audit,
 * challenge mode), all built on top of ClaimVerificationService's single verification pipeline.
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);
    /** Bounds audit-mode runtime/cost - claims beyond this count are still detected and counted
     *  toward Evidence Coverage, just not individually verified. */
    private static final int MAX_CLAIMS_PER_AUDIT = 12;

    private final ClaimRepository claimRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AnswerRepository answerRepository;
    private final ConversationRepository conversationRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final ClaimDecompositionService claimDecompositionService;
    private final ClaimVerificationService claimVerificationService;
    private final JsonUtil jsonUtil;
    private final Executor auditExecutor;
    private final Executor claimPipelineExecutor;

    public ClaimService(ClaimRepository claimRepository, ChatMessageRepository chatMessageRepository,
                         AnswerRepository answerRepository, ConversationRepository conversationRepository,
                         ChunkRepository chunkRepository, DocumentRepository documentRepository,
                         ClaimDecompositionService claimDecompositionService,
                         ClaimVerificationService claimVerificationService, JsonUtil jsonUtil,
                         @Qualifier("documentProcessingExecutor") Executor auditExecutor,
                         @Qualifier("claimPipelineExecutor") Executor claimPipelineExecutor) {
        this.claimRepository = claimRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.answerRepository = answerRepository;
        this.conversationRepository = conversationRepository;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.claimDecompositionService = claimDecompositionService;
        this.claimVerificationService = claimVerificationService;
        this.jsonUtil = jsonUtil;
        this.auditExecutor = auditExecutor;
        this.claimPipelineExecutor = claimPipelineExecutor;
    }

    public List<ClaimResponse> decompose(Document document, String userId, ClaimDecomposeRequest request) {
        boolean hasMessageId = request.messageId() != null && !request.messageId().isBlank();
        boolean hasClaimText = request.claimText() != null && !request.claimText().isBlank();
        if (hasMessageId == hasClaimText) {
            throw new BadRequestException("Provide exactly one of messageId or claimText.");
        }

        String sourceText;
        String linkedMessageId = null;

        if (hasMessageId) {
            ChatMessage message = chatMessageRepository.findById(request.messageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Message not found."));
            Conversation conversation = conversationRepository.findById(message.getConversationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Message not found."));
            if (!conversation.getUserId().equals(userId) || !conversation.getDocumentId().equals(document.getId())) {
                throw new ResourceNotFoundException("Message not found.");
            }
            Answer answer = answerRepository.findByMessageId(message.getId())
                    .orElseThrow(() -> new BadRequestException("This message has no document answer to decompose."));
            if (answer.isInsufficientContext()) {
                throw new BadRequestException("The document did not provide an answer for this message, so there is nothing to decompose.");
            }
            sourceText = answer.getDocumentClaim();
            linkedMessageId = message.getId();
        } else {
            sourceText = request.claimText();
        }

        List<AtomicClaim> atomicClaims = claimDecompositionService.decompose(sourceText);
        List<ClaimResponse> responses = new ArrayList<>();
        for (AtomicClaim atomicClaim : atomicClaims) {
            VerifiedClaim verified = claimVerificationService.verify(document.getId(), atomicClaim, ClaimMode.NORMAL);
            Claim saved = persist(document.getId(), linkedMessageId, null, verified);
            responses.add(toResponse(saved));
        }
        return responses;
    }

    public List<ClaimResponse> listClaims(Document document) {
        return claimRepository.findByDocumentIdOrderByCreatedAtAsc(document.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public AuditResponse getAuditStatus(Document document) {
        List<Claim> documentLevelClaims = claimRepository.findByDocumentIdOrderByCreatedAtAsc(document.getId()).stream()
                .filter(c -> c.getMessageId() == null)
                .toList();

        Map<String, Integer> verdictCounts = new LinkedHashMap<>();
        for (Claim c : documentLevelClaims) {
            verdictCounts.merge(c.getVerdict().name(), 1, Integer::sum);
        }

        int detected = document.getAuditClaimsDetected();
        int investigated = documentLevelClaims.size();
        double coverage = detected == 0 ? 0 : Math.round((investigated / (double) detected) * 10000.0) / 10000.0;

        return new AuditResponse(
                document.getAuditStatus().name(),
                detected,
                investigated,
                coverage,
                verdictCounts,
                document.getAuditFailureReason(),
                documentLevelClaims.stream().map(this::toResponse).toList()
        );
    }

    @Transactional
    public void startAudit(Document document) {
        document.setAuditStatus(AuditStatus.RUNNING);
        document.setAuditFailureReason(null);
        documentRepository.save(document);
        claimRepository.deleteByDocumentId(document.getId());
        String documentId = document.getId();
        auditExecutor.execute(() -> runAudit(documentId));
    }

    private void runAudit(String documentId) {
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) return;

        try {
            var chunks = chunkRepository.findByDocumentIdOrderByChunkOrderAsc(documentId);

            // Decomposition is independent per chunk, so run it concurrently instead of one LLM
            // call at a time - the dominant cost of an audit on a large document (one call per
            // chunk, easily 100+ for a long PDF).
            List<CompletableFuture<List<AtomicClaim>>> decomposeFutures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(
                            () -> claimDecompositionService.decompose(chunk.getText()), claimPipelineExecutor))
                    .toList();

            Set<String> seenClaimTexts = new LinkedHashSet<>();
            List<AtomicClaim> toVerify = new ArrayList<>();
            int totalDetected = 0;

            for (CompletableFuture<List<AtomicClaim>> future : decomposeFutures) {
                for (AtomicClaim ac : future.join()) {
                    String key = ac.claim().toLowerCase().trim();
                    if (key.isEmpty() || !seenClaimTexts.add(key)) continue;
                    totalDetected++;
                    if (toVerify.size() < MAX_CLAIMS_PER_AUDIT) {
                        toVerify.add(ac);
                    }
                }
            }

            // Same reasoning: each claim's verification (web search + classification) is
            // independent of the others, so run the bounded set of them concurrently too.
            List<CompletableFuture<VerifiedClaim>> verifyFutures = toVerify.stream()
                    .map(atomicClaim -> CompletableFuture.supplyAsync(
                            () -> claimVerificationService.verify(documentId, atomicClaim, ClaimMode.NORMAL),
                            claimPipelineExecutor))
                    .toList();

            for (CompletableFuture<VerifiedClaim> future : verifyFutures) {
                persist(documentId, null, null, future.join());
            }

            document.setAuditClaimsDetected(totalDetected);
            document.setAuditStatus(AuditStatus.DONE);
            documentRepository.save(document);
            log.info("Audit complete for document {}: {} claims detected, {} investigated", documentId, totalDetected, toVerify.size());
        } catch (Exception ex) {
            log.error("Audit failed for document {}: {}", documentId, ex.getMessage(), ex);
            document.setAuditStatus(AuditStatus.FAILED);
            document.setAuditFailureReason(safeMessage(ex));
            documentRepository.save(document);
        }
    }

    public ClaimResponse challenge(Document document, String claimId) {
        Claim original = claimRepository.findByIdAndDocumentId(claimId, document.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Claim not found."));

        AtomicClaim atomicClaim = new AtomicClaim(original.getClaimText(), original.getClaimType(), original.isTimeSensitive());
        VerifiedClaim verified = claimVerificationService.verify(document.getId(), atomicClaim, ClaimMode.CHALLENGE);
        Claim saved = persist(document.getId(), original.getMessageId(), original.getId(), verified);
        return toResponse(saved);
    }

    private Claim persist(String documentId, String messageId, String sourceClaimId, VerifiedClaim verified) {
        Claim claim = new Claim();
        claim.setDocumentId(documentId);
        claim.setMessageId(messageId);
        claim.setSourceClaimId(sourceClaimId);
        claim.setClaimText(verified.claimText());
        claim.setClaimType(verified.claimType());
        claim.setTimeSensitive(verified.timeSensitive());
        claim.setCheckable(true);
        claim.setMode(verified.mode());
        claim.setVerdict(verified.verdict());
        claim.setRetrievalConfidence(verified.retrievalConfidence());
        claim.setFidelityConfidence(verified.fidelityConfidence());
        claim.setWebConfidence(verified.webConfidence());
        claim.setSourceIndependenceScore(verified.sourceIndependenceScore());
        claim.setIndependentSourceCount(verified.independentSourceCount());
        claim.setRawSourceCount(verified.rawSourceCount());
        claim.setTemporalStatus(verified.temporalStatus());
        claim.setEvidenceJson(jsonUtil.toJson(toEvidenceDtos(verified.evidence())));
        claim.setSupportSourcesJson(jsonUtil.toJson(toSourceDtos(verified.supportSources())));
        claim.setCounterSourcesJson(jsonUtil.toJson(toSourceDtos(verified.counterSources())));
        claim.setRationale(verified.rationale());
        return claimRepository.save(claim);
    }

    private ClaimResponse toResponse(Claim c) {
        return new ClaimResponse(
                c.getId(), c.getDocumentId(), c.getMessageId(), c.getSourceClaimId(),
                c.getClaimText(), c.getClaimType(), c.isTimeSensitive(), c.isCheckable(), c.getMode().name(),
                c.getVerdict().name(), c.getRetrievalConfidence(), c.getFidelityConfidence(), c.getWebConfidence(),
                c.getSourceIndependenceScore(), c.getIndependentSourceCount(), c.getRawSourceCount(),
                c.getTemporalStatus().name(),
                jsonUtil.fromJson(c.getEvidenceJson(), new TypeReference<List<EvidenceDto>>() {}),
                jsonUtil.fromJson(c.getSupportSourcesJson(), new TypeReference<List<SourceDto>>() {}),
                jsonUtil.fromJson(c.getCounterSourcesJson(), new TypeReference<List<SourceDto>>() {}),
                c.getRationale(), c.getCreatedAt()
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

    private List<SourceDto> toSourceDtos(List<SourceEvaluation> sources) {
        return sources.stream()
                .map(s -> new SourceDto(s.url(), s.title(), s.snippet(), s.stance().name(), s.authorityTier().name(), s.publishedDate()))
                .toList();
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) message = ex.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
