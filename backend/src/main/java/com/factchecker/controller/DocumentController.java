package com.factchecker.controller;

import com.factchecker.domain.Document;
import com.factchecker.dto.DocumentResponse;
import com.factchecker.dto.StructureResponse;
import com.factchecker.security.AuthenticatedUser;
import com.factchecker.service.DocumentService;
import com.factchecker.service.DocumentTextService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentTextService documentTextService;

    public DocumentController(DocumentService documentService, DocumentTextService documentTextService) {
        this.documentService = documentService;
        this.documentTextService = documentTextService;
    }

    @GetMapping
    public List<DocumentResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return documentService.list(user.id()).stream().map(DocumentResponse::from).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @RequestParam("file") MultipartFile file) {
        Document document = documentService.upload(user.id(), file);
        return ResponseEntity.ok(DocumentResponse.from(document));
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        return DocumentResponse.from(documentService.get(user.id(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        documentService.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** The workspace Text pane: the document's extracted content as Markdown plus the typed block
     *  list. Computed on first call and cached (see DocumentTextService), so the first request on a
     *  large document is noticeably slower than subsequent ones. */
    @GetMapping("/{id}/structure")
    public StructureResponse structure(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        Document document = documentService.get(user.id(), id);
        return documentTextService.getStructure(document);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<FileSystemResource> getFile(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        Document document = documentService.get(user.id(), id);
        FileSystemResource resource = new FileSystemResource(document.getStoragePath());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + document.getFilename() + "\"")
                .body(resource);
    }
}
