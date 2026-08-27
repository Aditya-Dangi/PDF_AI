package com.factchecker.pdf;

import com.factchecker.dto.RectDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Groups extracted lines into paragraph-sized chunks for embedding and retrieval. Chunks never
 * cross a page boundary, which keeps every piece of retrieved evidence attributable to exactly
 * one page for highlighting.
 */
@Component
public class ChunkBuilder {

    private static final int TARGET_CHUNK_CHARS = 700;
    private static final int MAX_CHUNK_CHARS = 1100;
    /** A vertical gap larger than this multiple of the previous line's height signals a new paragraph. */
    private static final double PARAGRAPH_GAP_MULTIPLIER = 1.6;
    /** Table-of-contents / index lines ("Section 9.2 ....... 47") match on keywords but carry no
     *  real explanatory content - embedding them lets a broad question retrieve the ToC entry
     *  itself instead of the actual passage, producing a garbage "document claim". Matches a run of
     *  dot-leader characters followed by a trailing page number, wherever it falls in the line. */
    private static final Pattern TOC_DOT_LEADER = Pattern.compile("(\\.\\s?){4,}\\d{1,4}\\s*$");

    public List<ChunkCandidate> buildChunks(List<ExtractedPage> pages) {
        List<ChunkCandidate> chunks = new ArrayList<>();
        int order = 0;

        for (ExtractedPage page : pages) {
            List<ExtractedLine> lines = page.lines();
            int i = 0;
            while (i < lines.size()) {
                StringBuilder text = new StringBuilder();
                List<RectDto> rects = new ArrayList<>();
                boolean ocr = false;
                ExtractedLine previous = null;

                while (i < lines.size()) {
                    ExtractedLine line = lines.get(i);

                    if (TOC_DOT_LEADER.matcher(line.text().trim()).find()) {
                        i++;
                        continue;
                    }

                    boolean paragraphBreak = previous != null
                            && (line.y() - (previous.y() + previous.height())) > previous.height() * PARAGRAPH_GAP_MULTIPLIER;

                    if (paragraphBreak && text.length() >= TARGET_CHUNK_CHARS / 3) {
                        break;
                    }
                    if (text.length() + line.text().length() > MAX_CHUNK_CHARS && text.length() > 0) {
                        break;
                    }

                    if (text.length() > 0) text.append(' ');
                    text.append(line.text().trim());
                    rects.add(new RectDto(line.x(), line.y(), line.width(), line.height()));
                    ocr = ocr || line.ocr();
                    previous = line;
                    i++;

                    if (text.length() >= TARGET_CHUNK_CHARS) break;
                }

                String chunkText = text.toString().trim();
                if (!chunkText.isEmpty()) {
                    chunks.add(new ChunkCandidate(page.pageNumber(), order++, chunkText, rects, ocr));
                }
            }
        }

        return chunks;
    }
}
