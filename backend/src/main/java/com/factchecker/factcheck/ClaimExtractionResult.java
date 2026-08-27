package com.factchecker.factcheck;

public record ClaimExtractionResult(boolean checkable, String claim, String type, String reason) {
}
