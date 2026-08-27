package com.factchecker.pdf;

import com.factchecker.dto.RectDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChunkBuilder decides how the document gets sliced into the pieces that are embedded and later
 * cited as evidence, so its boundary decisions (page breaks, paragraph gaps, the max-length cap)
 * directly determine what a user sees highlighted. These tests build small synthetic pages rather
 * than real PDFs so the boundary logic itself is pinned down independently of PDFBox.
 */
class ChunkBuilderTest {

    private final ChunkBuilder chunkBuilder = new ChunkBuilder();

    @Test
    void emptyDocumentProducesNoChunks() {
        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of());

        assertThat(chunks).isEmpty();
    }

    @Test
    void pageWithOnlyBlankLinesProducesNoChunks() {
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                line(1, "   ", 0, 10),
                line(1, "", 15, 10)
        ));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page));

        assertThat(chunks).isEmpty();
    }

    @Test
    void closelySpacedShortLinesMergeIntoOneChunkInOrder() {
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                line(1, "First line.", 0, 10),
                line(1, "Second line.", 15, 10),
                line(1, "Third line.", 30, 10)
        ));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("First line. Second line. Third line.");
        assertThat(chunks.get(0).page()).isEqualTo(1);
        assertThat(chunks.get(0).order()).isZero();
        assertThat(chunks.get(0).rects()).hasSize(3);
    }

    @Test
    void aParagraphGapIsIgnoredWhileTheChunkIsStillShort() {
        // Gap between the two lines is 40pt, well past the 1.6x-of-line-height threshold - but
        // there isn't nearly enough accumulated text yet for the builder to bother splitting on it.
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                line(1, "Short.", 0, 10),
                line(1, "Also short, but far below.", 50, 10)
        ));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("Short. Also short, but far below.");
    }

    @Test
    void aParagraphGapSplitsTheChunkOnceEnoughTextHasAccumulated() {
        String longFirstLine = "x".repeat(250); // past the TARGET_CHUNK_CHARS/3 (~233) split threshold
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                line(1, longFirstLine, 0, 10),
                line(1, "New paragraph after a big gap.", 50, 10)
        ));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(longFirstLine);
        assertThat(chunks.get(0).order()).isEqualTo(0);
        assertThat(chunks.get(1).text()).isEqualTo("New paragraph after a big gap.");
        assertThat(chunks.get(1).order()).isEqualTo(1);
    }

    @Test
    void lineThatWouldExceedMaxChunkLengthStartsANewChunkInstead() {
        String sixHundredChars = "a".repeat(600);
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                line(1, sixHundredChars, 0, 10),
                // Small gap - no paragraph break signal, this split is purely about the length cap.
                line(1, sixHundredChars, 12, 10)
        ));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo(sixHundredChars);
        assertThat(chunks.get(1).text()).isEqualTo(sixHundredChars);
    }

    @Test
    void chunksNeverCrossAPageBoundaryAndOrderKeepsIncreasingAcrossPages() {
        ExtractedPage page1 = new ExtractedPage(1, 612, 792, List.of(line(1, "Page one text.", 0, 10)));
        ExtractedPage page2 = new ExtractedPage(2, 612, 792, List.of(line(2, "Page two text.", 0, 10)));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page1, page2));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).page()).isEqualTo(1);
        assertThat(chunks.get(0).order()).isEqualTo(0);
        assertThat(chunks.get(1).page()).isEqualTo(2);
        assertThat(chunks.get(1).order()).isEqualTo(1);
    }

    @Test
    void ocrFlagIsTrueIfAnyContributingLineWasOcrSourced() {
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                new ExtractedLine(1, "Native text.", 0, 0, 100, 10, false, 0, false),
                new ExtractedLine(1, "Scanned text.", 0, 15, 100, 10, true, 0, false)
        ));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).ocr()).isTrue();
    }

    @Test
    void rectsPreserveTheOriginalLineCoordinatesForHighlighting() {
        ExtractedPage page = new ExtractedPage(1, 612, 792, List.of(
                new ExtractedLine(1, "Some text.", 44, 300, 220, 7, false, 0, false)
        ));

        List<ChunkCandidate> chunks = chunkBuilder.buildChunks(List.of(page));

        RectDto rect = chunks.get(0).rects().get(0);
        assertThat(rect.x()).isEqualTo(44);
        assertThat(rect.y()).isEqualTo(300);
        assertThat(rect.width()).isEqualTo(220);
        assertThat(rect.height()).isEqualTo(7);
    }

    private ExtractedLine line(int page, String text, double y, double height) {
        return new ExtractedLine(page, text, 0, y, 100, height, false, 0, false);
    }
}
