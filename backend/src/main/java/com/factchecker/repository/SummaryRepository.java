package com.factchecker.repository;

import com.factchecker.domain.Summary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, String> {
    Optional<Summary> findByMessageId(String messageId);
    void deleteByMessageId(String messageId);
}
