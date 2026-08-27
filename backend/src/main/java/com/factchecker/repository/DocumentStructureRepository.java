package com.factchecker.repository;

import com.factchecker.domain.DocumentStructure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentStructureRepository extends JpaRepository<DocumentStructure, String> {
    Optional<DocumentStructure> findByDocumentId(String documentId);
    void deleteByDocumentId(String documentId);
}
