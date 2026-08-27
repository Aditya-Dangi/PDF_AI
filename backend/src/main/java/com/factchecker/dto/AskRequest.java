package com.factchecker.dto;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(@NotBlank String question, AnswerMode mode) {

    /** Absent or unknown mode means FAST: an older client, or a caller that never opted in, must
     *  keep getting the original single-pass behavior rather than silently paying for deep research. */
    public AnswerMode modeOrDefault() {
        return mode == null ? AnswerMode.FAST : mode;
    }
}
