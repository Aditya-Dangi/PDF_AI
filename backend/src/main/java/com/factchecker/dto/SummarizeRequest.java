package com.factchecker.dto;

import jakarta.validation.constraints.NotBlank;

public record SummarizeRequest(@NotBlank String text) {
}
