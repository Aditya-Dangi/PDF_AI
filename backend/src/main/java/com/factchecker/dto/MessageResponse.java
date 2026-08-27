package com.factchecker.dto;

import java.time.Instant;

public record MessageResponse(
        String id,
        String role,
        String content,
        Instant createdAt,
        AnswerResponse answer,
        FactCheckResponse factCheck,
        SummaryResponse summary
) {
}
