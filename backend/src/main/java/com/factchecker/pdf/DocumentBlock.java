package com.factchecker.pdf;

import com.factchecker.dto.RectDto;

import java.util.List;

/**
 * One structural block of a document - a heading, paragraph, list item, or running header/footer.
 *
 * @param headingLevel 1-6 for {@link BlockType#HEADING} (mapped to Markdown {@code #}..{@code ######}),
 *                     0 for every other type.
 * @param rects        the source line boxes this block was built from, so the UI can highlight the
 *                     block on the rendered page using the same overlay path as answer evidence.
 */
public record DocumentBlock(
        int page,
        BlockType type,
        int headingLevel,
        String text,
        List<RectDto> rects
) {
}
