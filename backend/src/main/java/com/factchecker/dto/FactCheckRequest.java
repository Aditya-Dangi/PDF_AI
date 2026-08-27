package com.factchecker.dto;

/** Exactly one of messageId (fact-check a previous answer's document claim) or claimText (a free-typed claim) must be set. */
public record FactCheckRequest(String messageId, String claimText) {
}
