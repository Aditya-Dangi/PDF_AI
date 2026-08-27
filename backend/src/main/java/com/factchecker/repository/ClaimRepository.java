package com.factchecker.repository;

import com.factchecker.domain.Claim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClaimRepository extends JpaRepository<Claim, String> {
    List<Claim> findByDocumentIdOrderByCreatedAtAsc(String documentId);
    List<Claim> findByMessageIdOrderByCreatedAtAsc(String messageId);
    Optional<Claim> findByIdAndDocumentId(String id, String documentId);
    void deleteByDocumentId(String documentId);
}
