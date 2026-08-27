package com.factchecker.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Named ChatMessage (not Message) to avoid any ambiguity with java.lang / messaging libraries. */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Lob
    @Column(nullable = false)
    private String content;

    /** For an ASSISTANT message, the id of the USER message it answers - null for a USER message
     *  itself. Lets deleteMessage() remove a question+answer pair together without guessing at
     *  adjacency by timestamp. Nullable so adding this column via ddl-auto=update doesn't require
     *  backfilling pre-existing rows (an old assistant message just can't be pair-deleted). */
    private String replyToMessageId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
