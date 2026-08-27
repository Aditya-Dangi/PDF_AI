package com.factchecker.repository;

import com.factchecker.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
    Optional<Conversation> findByDocumentIdAndUserId(String documentId, String userId);
    List<Conversation> findByDocumentId(String documentId);
}
