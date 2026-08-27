package com.factchecker.repository;

import com.factchecker.domain.Answer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnswerRepository extends JpaRepository<Answer, String> {
    Optional<Answer> findByMessageId(String messageId);
}
