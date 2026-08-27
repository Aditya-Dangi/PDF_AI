package com.factchecker.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the structure heuristics - no LLM, no database, no PDF. Each test builds
 * synthetic pages with the exact font sizes and positions that drive one rule, so a failure points
 * at that rule rather than at "the pipeline".
 */
class DocumentStructureServiceTest {

    private final DocumentStructureService service = new DocumentStructureService();

    private static final double BODY_SIZE = 10.0;
    private static final double H1_SIZE = 20.0;
    private static final double H2_SIZE = 14.0;
    private static final double LINE_HEIGHT = 12.0;

    @Test
    @DisplayName("classifies larger text as headings and ranks levels by descending size")
    void detectsHeadingsAndLevels() {
        ExtractedPage page = page(1,
                line(1, "Chapter One", 100, H1_SIZE),
                line(1, "Section A", 130, H2_SIZE),
                line(1, "Some ordinary body prose here.", 160, BODY_SIZE));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(page));

        assertThat(blocks).extracting(DocumentBlock::type)
                .containsExactly(BlockType.HEADING, BlockType.HEADING, BlockType.PARAGRAPH);
        assertThat(blocks.get(0).headingLevel()).isEqualTo(1);
        assertThat(blocks.get(1).headingLevel()).isEqualTo(2);
        assertThat(blocks.get(2).headingLevel()).isZero();
    }

    @Test
    @DisplayName("body size is the size covering the most characters, not the most lines")
    void bodySizeIsWeightedByCharacters() {
        // Three short large-font headings outnumber the two body lines, so a naive "most common
        // line size" would elect the heading size as body and inverting every classification.
        ExtractedPage page = page(1,
                line(1, "One", 100, H1_SIZE),
                line(1, "Two", 120, H1_SIZE),
                line(1, "Three", 140, H1_SIZE),
                line(1, "A considerably longer sentence of actual body copy that carries the document.", 200, BODY_SIZE),
                line(1, "Another substantial sentence of body copy continuing the same paragraph here.", 260, BODY_SIZE));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(page));

        assertThat(blocks).filteredOn(b -> b.type() == BlockType.HEADING).hasSize(3);
        assertThat(blocks).filteredOn(b -> b.type() == BlockType.PARAGRAPH).isNotEmpty();
    }

    @Test
    @DisplayName("a numbered section heading stays a heading, not a list item")
    void numberedHeadingIsNotDemotedToListItem() {
        // "1. Hashing" matches the ordered-list pattern exactly, so precedence between the two rules
        // decides whether every numbered section heading in a document becomes a bullet point.
        ExtractedPage page = page(1,
                line(1, "1. Hashing", 100, H2_SIZE),
                line(1, "1. A genuine numbered list item.", 140, BODY_SIZE));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(page));

        assertThat(blocks.get(0).type()).isEqualTo(BlockType.HEADING);
        assertThat(blocks.get(0).text()).isEqualTo("1. Hashing");
        assertThat(blocks.get(1).type()).isEqualTo(BlockType.LIST_ITEM);
    }

    @Test
    @DisplayName("detects bullet and numbered list items and strips their markers")
    void detectsListItems() {
        ExtractedPage page = page(1,
                line(1, "• First point", 100, BODY_SIZE),
                line(1, "2. Second point", 130, BODY_SIZE),
                line(1, "- Third point", 160, BODY_SIZE));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(page));

        assertThat(blocks).allMatch(b -> b.type() == BlockType.LIST_ITEM);
        assertThat(blocks).extracting(DocumentBlock::text)
                .containsExactly("First point", "Second point", "Third point");
    }

    @Test
    @DisplayName("removes a running footer whose page number varies between pages")
    void detectsRepeatedFooterDespiteVaryingPageNumber() {
        // The whole point of normalizing digits: "Page 1".."Page 4" are the same footer.
        List<ExtractedPage> pages = new ArrayList<>();
        for (int p = 1; p <= 4; p++) {
            pages.add(page(p,
                    line(p, "Body content for page " + p + " goes here.", 350, BODY_SIZE),
                    line(p, "Page " + p, 700, BODY_SIZE)));
        }

        List<DocumentBlock> blocks = service.buildBlocks(pages);

        assertThat(blocks).filteredOn(b -> b.type() == BlockType.HEADER_FOOTER).hasSize(4);
        assertThat(service.toMarkdown(blocks)).doesNotContain("Page 1", "Page 4");
    }

    @Test
    @DisplayName("keeps mid-page body text that differs only by a number")
    void doesNotRemoveMidPageBodyTextDifferingOnlyByNumber() {
        // Digit normalization makes "…for page 1" and "…for page 4" identical keys, so without the
        // page-margin constraint this recurring mid-page prose would be stripped as a running head.
        List<ExtractedPage> pages = new ArrayList<>();
        for (int p = 1; p <= 4; p++) {
            pages.add(page(p, line(p, "Body content for page " + p + " goes here.", 350, BODY_SIZE)));
        }

        List<DocumentBlock> blocks = service.buildBlocks(pages);

        assertThat(blocks).noneMatch(b -> b.type() == BlockType.HEADER_FOOTER);
        assertThat(service.toMarkdown(blocks)).contains("Body content for page 1");
    }

    @Test
    @DisplayName("keeps repeated text that appears at a different y-position on each page")
    void doesNotRemoveRepeatedTextAtDifferentPositions() {
        // Same words, scattered mid-page: legitimate recurring prose, not a running head. Matching
        // on text alone would delete all of it.
        List<ExtractedPage> pages = List.of(
                page(1, line(1, "Important note", 200, BODY_SIZE)),
                page(2, line(2, "Important note", 400, BODY_SIZE)),
                page(3, line(3, "Important note", 600, BODY_SIZE)));

        List<DocumentBlock> blocks = service.buildBlocks(pages);

        assertThat(blocks).noneMatch(b -> b.type() == BlockType.HEADER_FOOTER);
    }

    @Test
    @DisplayName("keeps a line that sits at a common y-position but says something different")
    void doesNotRemoveDistinctTextAtSamePosition() {
        // Matching on position alone would delete these - they are the first body line of each page.
        List<ExtractedPage> pages = List.of(
                page(1, line(1, "Introduction to hashing", 100, BODY_SIZE)),
                page(2, line(2, "Sliding window technique", 100, BODY_SIZE)),
                page(3, line(3, "Binary search fundamentals", 100, BODY_SIZE)));

        List<DocumentBlock> blocks = service.buildBlocks(pages);

        assertThat(blocks).noneMatch(b -> b.type() == BlockType.HEADER_FOOTER);
    }

    @Test
    @DisplayName("never treats a single page's lines as repeated headers")
    void singlePageHasNoHeaderFooter() {
        ExtractedPage page = page(1,
                line(1, "Title", 50, BODY_SIZE),
                line(1, "Title", 700, BODY_SIZE));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(page));

        assertThat(blocks).noneMatch(b -> b.type() == BlockType.HEADER_FOOTER);
    }

    @Test
    @DisplayName("merges consecutive body lines into one paragraph but splits on a large gap")
    void mergesParagraphsAndSplitsOnGap() {
        ExtractedPage page = page(1,
                line(1, "First sentence of the paragraph.", 100, BODY_SIZE),
                line(1, "Second sentence, tightly following.", 114, BODY_SIZE),
                line(1, "A separate paragraph after a wide gap.", 300, BODY_SIZE));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(page));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).text())
                .isEqualTo("First sentence of the paragraph. Second sentence, tightly following.");
        assertThat(blocks.get(0).rects()).hasSize(2);
        assertThat(blocks.get(1).text()).isEqualTo("A separate paragraph after a wide gap.");
    }

    @Test
    @DisplayName("adapts paragraph merging to a loosely-set document's own line spacing")
    void mergesParagraphsInALooselySetDocument() {
        // PDFBox reports glyph height (~6pt for 11pt text), not line pitch (~18pt here). Judging the
        // break against glyph height would split every one of these lines into its own paragraph;
        // judging it against the document's own typical gap keeps them together.
        List<ExtractedLine> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(new ExtractedLine(1, "Line " + i + " of a normally spaced paragraph.",
                    50, 100 + i * 18.0, 400, 6.36, false, BODY_SIZE, false));
        }
        // A clearly larger gap must still break, so the rule stays useful rather than merging all.
        lines.add(new ExtractedLine(1, "A genuinely separate paragraph.",
                50, 100 + 6 * 18.0 + 60, 400, 6.36, false, BODY_SIZE, false));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(new ExtractedPage(1, 612, 792, lines)));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).rects()).hasSize(6);
        assertThat(blocks.get(1).text()).isEqualTo("A genuinely separate paragraph.");
    }

    @Test
    @DisplayName("OCR lines carry no font metrics and are treated as body text, never headings")
    void ocrLinesAreNeverHeadings() {
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                new ExtractedLine(1, "Scanned line one", 50, 100, 400, LINE_HEIGHT, true, 0, false),
                new ExtractedLine(1, "Scanned line two", 50, 300, 400, LINE_HEIGHT, true, 0, false)));

        List<DocumentBlock> blocks = service.buildBlocks(List.of(page));

        assertThat(blocks).noneMatch(b -> b.type() == BlockType.HEADING);
    }

    @Test
    @DisplayName("renders Markdown with heading levels and list markers, excluding running heads")
    void rendersMarkdown() {
        List<ExtractedPage> pages = List.of(
                page(1,
                        line(1, "Guide", 100, H1_SIZE),
                        line(1, "Overview", 140, H2_SIZE),
                        line(1, "Some explanatory prose.", 180, BODY_SIZE),
                        line(1, "• A bullet", 260, BODY_SIZE),
                        line(1, "Confidential", 700, BODY_SIZE)),
                page(2,
                        line(2, "More prose on the second page.", 180, BODY_SIZE),
                        line(2, "Confidential", 700, BODY_SIZE)));

        String markdown = service.toMarkdown(service.buildBlocks(pages));

        assertThat(markdown).contains("# Guide");
        assertThat(markdown).contains("## Overview");
        assertThat(markdown).contains("- A bullet");
        assertThat(markdown).doesNotContain("Confidential");
    }

    @Test
    @DisplayName("returns nothing for a document with no extractable lines")
    void emptyDocumentYieldsNoBlocks() {
        assertThat(service.buildBlocks(List.of())).isEmpty();
        assertThat(service.buildBlocks(List.of(new ExtractedPage(1, 612, 792, List.of())))).isEmpty();
    }

    private static ExtractedPage page(int pageNumber, ExtractedLine... lines) {
        return new ExtractedPage(pageNumber, 612, 792, List.of(lines));
    }

    private static ExtractedLine line(int pageNumber, String text, double y, double fontSize) {
        return new ExtractedLine(pageNumber, text, 50, y, 400, LINE_HEIGHT, false, fontSize, false);
    }
}
