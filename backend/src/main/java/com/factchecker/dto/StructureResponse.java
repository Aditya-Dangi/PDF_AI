package com.factchecker.dto;

import java.util.List;

/**
 * The document's extracted content in both renderings the Text pane offers.
 *
 * <p>Both are returned in one response so switching between the Markdown and JSON tabs costs no
 * extra round trip - they are two views of the same computed structure, not two separate queries.
 */
public record StructureResponse(
        String markdown,
        List<DocumentBlockDto> blocks
) {
}
