package com.factchecker.repository;

import com.factchecker.domain.Document;
import com.factchecker.domain.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<Document> findByIdAndUserId(String id, String userId);
    List<Document> findByStatus(DocumentStatus status);
}
