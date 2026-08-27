package com.factchecker.pdf;

/**
 * Structural role of a block of document text, inferred heuristically by DocumentStructureService.
 *
 * <p>Deliberately a small set. These are the roles that can be inferred reliably from font size,
 * position, and line prefixes alone - the ones a reader would notice being wrong in the rendered
 * Markdown. Richer layout roles (table, figure, caption) need a real layout model and are out of
 * scope; nothing here reports a confidence score, because these heuristics cannot honestly produce
 * one.
 */
public enum BlockType {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    /** Repeated running head/foot (page numbers, document title on every page). Excluded from the
     *  Markdown rendering, but still returned in the block list so nothing is silently lost. */
    HEADER_FOOTER
}
