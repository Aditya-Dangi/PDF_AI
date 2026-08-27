package com.factchecker.dto;

/** Exactly one of messageId (decompose a previous answer's document claim) or claimText (free-typed text) must be set. */
public record ClaimDecomposeRequest(String messageId, String claimText) {
}
