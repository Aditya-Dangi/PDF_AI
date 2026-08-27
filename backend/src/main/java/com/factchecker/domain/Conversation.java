package com.factchecker.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
public class Conversation {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String documentId;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
