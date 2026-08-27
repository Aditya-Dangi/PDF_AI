package com.factchecker.repository;

import com.factchecker.domain.FactCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FactCheckRepository extends JpaRepository<FactCheck, String> {
    Optional<FactCheck> findByMessageId(String messageId);
    void deleteByMessageId(String messageId);
}
