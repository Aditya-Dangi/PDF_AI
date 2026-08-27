package com.factchecker.repository;

import com.factchecker.domain.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, String> {
    List<Chunk> findByDocumentIdOrderByChunkOrderAsc(String documentId);
    void deleteByDocumentId(String documentId);
}
