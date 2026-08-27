package com.factchecker.integration;

import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import com.factchecker.dto.AuthResponse;
import com.factchecker.dto.RegisterRequest;
import com.factchecker.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Automates the cross-user isolation check this app's whole multi-user design hinges on ("do not
 * expose one user's documents to another user"). Uploading through the real endpoint would pull in
 * the async PDFBox/OCR/Ollama pipeline as an external dependency this test shouldn't need, so a
 * Document row is inserted directly via the repository - what's under test here is the
 * authorization boundary in DocumentService/DocumentController, not the processing pipeline
 * (that's covered separately by ChunkBuilderTest and manual end-to-end verification).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private String userAToken;
    private String userAId;
    private String userBToken;
    private String ownedByUserADocId;

    @BeforeEach
    void setUp() throws Exception {
        AuthResponse userA = register("owner-" + UUID.randomUUID() + "@example.com");
        AuthResponse userB = register("intruder-" + UUID.randomUUID() + "@example.com");
        userAToken = userA.token();
        userAId = userA.userId();
        userBToken = userB.token();

        Document doc = new Document();
        doc.setUserId(userAId);
        doc.setFilename("private-report.pdf");
        doc.setStoragePath("/does/not/need/to/exist/for/this/test.pdf");
        doc.setPageCount(3);
        doc.setStatus(DocumentStatus.READY);
        documentRepository.save(doc);
        ownedByUserADocId = doc.getId();
    }

    @Test
    void ownerCanReadTheirOwnDocumentMetadata() throws Exception {
        mockMvc.perform(get("/api/documents/{id}", ownedByUserADocId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownedByUserADocId))
                .andExpect(jsonPath("$.filename").value("private-report.pdf"));
    }

    @Test
    void ownerCanDeleteTheirOwnDocument() throws Exception {
        // Regression test: deleteByDocumentId derived repository queries (chunks, claims) require an
        // active transaction to execute their remove() calls - DocumentService.delete() previously
        // wasn't @Transactional and threw at runtime despite compiling fine and passing every other test.
        mockMvc.perform(delete("/api/documents/{id}", ownedByUserADocId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/documents/{id}", ownedByUserADocId)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerSeesTheDocumentInTheirOwnList() throws Exception {
        mockMvc.perform(get("/api/documents").header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + ownedByUserADocId + "')]").exists());
    }

    @Test
    void anotherUserGetsANotFoundNotAForbiddenForMetadata() throws Exception {
        // 404 rather than 403 on purpose: it must not even reveal that a document with this id exists.
        mockMvc.perform(get("/api/documents/{id}", ownedByUserADocId)
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUserGetsANotFoundForTheFileEndpointToo() throws Exception {
        mockMvc.perform(get("/api/documents/{id}/file", ownedByUserADocId)
                        .header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUsersDocumentListDoesNotIncludeIt() throws Exception {
        mockMvc.perform(get("/api/documents").header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + ownedByUserADocId + "')]").doesNotExist());
    }

    @Test
    void anotherUserCannotAskAQuestionAboutIt() throws Exception {
        mockMvc.perform(post("/api/documents/{id}/ask", ownedByUserADocId)
                        .header("Authorization", "Bearer " + userBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What does this say?\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRequestWithNoTokenAtAllIsRejected() throws Exception {
        mockMvc.perform(get("/api/documents/{id}", ownedByUserADocId))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aRequestWithAGarbageTokenIsRejectedRatherThanCrashing() throws Exception {
        mockMvc.perform(get("/api/documents/{id}", ownedByUserADocId)
                        .header("Authorization", "Bearer this-is-not-a-real-jwt"))
                .andExpect(status().is4xxClientError());
    }

    private AuthResponse register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, "password123"));
        String json = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, AuthResponse.class);
    }
}
