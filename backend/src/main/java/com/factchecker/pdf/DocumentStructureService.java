package com.factchecker.pdf;

import com.factchecker.dto.RectDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Infers a document's structure (headings, paragraphs, lists, running headers/footers) from the
 * extracted lines, and renders it as Markdown.
 *
 * <p>This is deliberately heuristic rather than a trained layout model: font size, position and
 * line prefixes are enough to produce a readable document, and they are explainable when wrong.
 * Nothing here emits a confidence score, because these rules cannot honestly produce one.
 */
@Service
public class DocumentStructureService {

    /** A line must exceed the body text size by this much to count as a heading. Below it, normal
     *  inter-line size jitter (superscripts, inline symbols) would produce spurious headings. */
    private static final double HEADING_SIZE_RATIO = 1.15;
    /** Markdown supports 6 heading levels; deeper size tiers all collapse to level 6. */
    private static final int MAX_HEADING_LEVEL = 6;
    /** Vertical tolerance, in points, for treating two lines as being at "approximately" the same
     *  y-position across pages. Roughly one line height, so a running head that shifts slightly
     *  between pages still groups together. */
    private static final double Y_POSITION_TOLERANCE = 12.0;
    /** A repeated line must appear on at least this fraction of pages to count as a running
     *  header/footer, and never on fewer than {@link #MIN_REPEAT_PAGES} pages. */
    private static final double HEADER_FOOTER_PAGE_FRACTION = 0.30;
    /** Repetition cannot be judged from a single page, so require at least two. This also stops a
     *  1-2 page document from having its only copy of a line mistaken for a running head. */
    private static final int MIN_REPEAT_PAGES = 2;
    /** Only lines within this fraction of the page height from the top or bottom edge can be
     *  running headers/footers. Real running heads live in the margins; without this, body text
     *  that differs only by a number ("Body content for page 3") and happens to sit at a consistent
     *  height would normalize to the same key on every page and be stripped from the document. */
    private static final double HEADER_FOOTER_MARGIN_FRACTION = 0.15;
    /**
     * A vertical gap larger than this multiple of the document's own typical line gap starts a new
     * paragraph.
     *
     * <p>Measured against the document's actual line spacing rather than the line's height, because
     * PDFBox reports glyph height (roughly 0.6x the font size), not line pitch. A fixed ratio
     * against glyph height silently depends on the document's leading - the same rule that merges
     * correctly in a tightly-set document splits every single line in a loosely-set one.
     */
    private static final double PARAGRAPH_BREAK_GAP_RATIO = 1.8;
    /** Used when a document has too few lines to measure its own spacing. */
    private static final double FALLBACK_LINE_GAP = 4.0;
    /** Gaps beyond this are column breaks or section spacing, not line spacing, and would skew the
     *  median upward if included in the measurement. */
    private static final double MAX_MEASURABLE_LINE_GAP = 40.0;

    private static final Pattern BULLET_PREFIX =
            Pattern.compile("^\\s*([\\u2022\\u2023\\u25E6\\u25AA\\u25CF\\u00B7*\\-\\u2013\\u2014]|\\(?\\d{1,3}[.)]|[a-zA-Z][.)])\\s+");
    /** Runs of digits are replaced when normalizing, so "Page 9" and "Page 10" - the same running
     *  footer with a varying page number - compare equal. Without this, page-numbered footers
     *  would never repeat exactly and would never be detected. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    public List<DocumentBlock> buildBlocks(List<ExtractedPage> pages) {
        List<ExtractedLine> allLines = pages.stream().flatMap(p -> p.lines().stream()).toList();
        if (allLines.isEmpty()) return List.of();

        double bodySize = bodyFontSize(allLines);
        Set<String> headerFooterKeys = headerFooterKeys(pages);
        List<Double> headingSizes = headingSizeTiers(allLines, bodySize, headerFooterKeys);
        double paragraphBreakGap = typicalLineGap(pages) * PARAGRAPH_BREAK_GAP_RATIO;

        List<DocumentBlock> blocks = new ArrayList<>();
        for (ExtractedPage page : pages) {
            ParagraphAccumulator paragraph = new ParagraphAccumulator(page.pageNumber(), paragraphBreakGap);

            for (ExtractedLine line : page.lines()) {
                if (line.text().isBlank()) continue;

                BlockType type = classify(line, bodySize, headerFooterKeys);
                if (type == BlockType.PARAGRAPH && paragraph.continues(line)) {
                    paragraph.add(line);
                    continue;
                }

                paragraph.flushInto(blocks);
                switch (type) {
                    case PARAGRAPH -> paragraph.add(line);
                    case HEADING -> blocks.add(new DocumentBlock(
                            line.page(), BlockType.HEADING, headingLevel(line.fontSize(), headingSizes),
                            line.text().trim(), List.of(rectOf(line))));
                    case LIST_ITEM -> blocks.add(new DocumentBlock(
                            line.page(), BlockType.LIST_ITEM, 0,
                            stripBullet(line.text()), List.of(rectOf(line))));
                    case HEADER_FOOTER -> blocks.add(new DocumentBlock(
                            line.page(), BlockType.HEADER_FOOTER, 0,
                            line.text().trim(), List.of(rectOf(line))));
                }
            }
            paragraph.flushInto(blocks);
        }
        return blocks;
    }

    public String toMarkdown(List<DocumentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        BlockType previous = null;

        for (DocumentBlock block : blocks) {
            // Running heads/feet are structural noise once the document is linearized - keeping
            // them would interleave "Page 12" through the prose.
            if (block.type() == BlockType.HEADER_FOOTER) continue;

            if (previous != null && !(previous == BlockType.LIST_ITEM && block.type() == BlockType.LIST_ITEM)) {
                sb.append("\n\n");
            } else if (previous != null) {
                sb.append('\n');
            }

            switch (block.type()) {
                case HEADING -> sb.append("#".repeat(block.headingLevel())).append(' ').append(block.text());
                case LIST_ITEM -> sb.append("- ").append(block.text());
                default -> sb.append(block.text());
            }
            previous = block.type();
        }
        return sb.toString();
    }

    private BlockType classify(ExtractedLine line, double bodySize, Set<String> headerFooterKeys) {
        if (headerFooterKeys.contains(positionKey(line))) return BlockType.HEADER_FOOTER;
        // Size is checked BEFORE the list-marker pattern, because numbered section headings
        // ("1. Hashing", "2.3 Methods") match the ordered-list pattern exactly. Testing the marker
        // first would demote every numbered heading in the document to a bullet point; being set in
        // larger type is the stronger and more reliable signal of the two.
        if (line.fontSize() > bodySize * HEADING_SIZE_RATIO) return BlockType.HEADING;
        if (BULLET_PREFIX.matcher(line.text()).find()) return BlockType.LIST_ITEM;
        return BlockType.PARAGRAPH;
    }

    /** Body size is the size covering the most *characters*, not the most lines: a document with
     *  many short headings and few long paragraphs would otherwise elect a heading size as "body"
     *  and invert the whole classification. */
    private double bodyFontSize(List<ExtractedLine> lines) {
        Map<Double, Integer> weight = new HashMap<>();
        for (ExtractedLine line : lines) {
            if (line.fontSize() <= 0) continue;
            weight.merge(line.fontSize(), line.text().trim().length(), Integer::sum);
        }
        return weight.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0.0);
    }

    /** Distinct above-body sizes, largest first - index 0 becomes {@code #}, index 1 {@code ##}, etc. */
    private List<Double> headingSizeTiers(List<ExtractedLine> lines, double bodySize, Set<String> headerFooterKeys) {
        Set<Double> sizes = new TreeSet<>(Comparator.reverseOrder());
        for (ExtractedLine line : lines) {
            if (line.text().isBlank()) continue;
            if (headerFooterKeys.contains(positionKey(line))) continue;
            if (line.fontSize() > bodySize * HEADING_SIZE_RATIO) sizes.add(line.fontSize());
        }
        return List.copyOf(sizes);
    }

    private int headingLevel(double fontSize, List<Double> headingSizes) {
        int index = headingSizes.indexOf(fontSize);
        if (index < 0) return MAX_HEADING_LEVEL;
        return Math.min(index + 1, MAX_HEADING_LEVEL);
    }

    /**
     * Identifies running headers/footers: lines whose normalized text repeats at approximately the
     * same y-position across enough pages.
     *
     * <p>Both text and position must agree. Matching on position alone would strip legitimate
     * repeated content that merely happens to sit at a consistent height - recurring section
     * labels, repeated field names in forms - and matching on text alone would strip a phrase that
     * genuinely recurs throughout the prose.
     */
    private Set<String> headerFooterKeys(List<ExtractedPage> pages) {
        int pageCount = pages.size();
        if (pageCount < MIN_REPEAT_PAGES) return Set.of();

        Map<String, Set<Integer>> pagesPerKey = new HashMap<>();
        for (ExtractedPage page : pages) {
            for (ExtractedLine line : page.lines()) {
                if (line.text().isBlank()) continue;
                if (!inPageMargin(line, page.height())) continue;
                pagesPerKey.computeIfAbsent(positionKey(line), k -> new HashSet<>()).add(line.page());
            }
        }

        int threshold = Math.max(MIN_REPEAT_PAGES, (int) Math.ceil(pageCount * HEADER_FOOTER_PAGE_FRACTION));
        Set<String> keys = new HashSet<>();
        pagesPerKey.forEach((key, pagesSeen) -> {
            if (pagesSeen.size() >= threshold) keys.add(key);
        });
        return keys;
    }

    private boolean inPageMargin(ExtractedLine line, double pageHeight) {
        if (pageHeight <= 0) return false;
        double margin = pageHeight * HEADER_FOOTER_MARGIN_FRACTION;
        return line.y() < margin || line.y() > pageHeight - margin;
    }

    /** The median gap between consecutive lines, i.e. this document's own normal line spacing.
     *  Median rather than mean so section breaks and page transitions do not drag it upward. */
    private double typicalLineGap(List<ExtractedPage> pages) {
        List<Double> gaps = new ArrayList<>();
        for (ExtractedPage page : pages) {
            List<ExtractedLine> lines = page.lines();
            for (int i = 1; i < lines.size(); i++) {
                ExtractedLine previous = lines.get(i - 1);
                double gap = lines.get(i).y() - (previous.y() + previous.height());
                if (gap > 0 && gap <= MAX_MEASURABLE_LINE_GAP) gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) return FALLBACK_LINE_GAP;
        Collections.sort(gaps);
        return gaps.get(gaps.size() / 2);
    }

    private String positionKey(ExtractedLine line) {
        long band = Math.round(line.y() / Y_POSITION_TOLERANCE);
        return normalizeText(line.text()) + "@" + band;
    }

    private String normalizeText(String text) {
        String collapsed = WHITESPACE_RUN.matcher(text.trim().toLowerCase()).replaceAll(" ");
        return DIGIT_RUN.matcher(collapsed).replaceAll("#");
    }

    private String stripBullet(String text) {
        return BULLET_PREFIX.matcher(text).replaceFirst("").trim();
    }

    private static RectDto rectOf(ExtractedLine line) {
        return new RectDto(line.x(), line.y(), line.width(), line.height());
    }

    /** Collects consecutive body lines into one paragraph block, breaking on a large vertical gap. */
    private static final class ParagraphAccumulator {
        private final int page;
        private final double breakGap;
        private final List<ExtractedLine> lines = new ArrayList<>();

        ParagraphAccumulator(int page, double breakGap) {
            this.page = page;
            this.breakGap = breakGap;
        }

        boolean continues(ExtractedLine next) {
            if (lines.isEmpty()) return false;
            ExtractedLine last = lines.get(lines.size() - 1);
            double gap = next.y() - (last.y() + last.height());
            return gap <= breakGap;
        }

        void add(ExtractedLine line) {
            lines.add(line);
        }

        void flushInto(List<DocumentBlock> blocks) {
            if (lines.isEmpty()) return;
            String text = String.join(" ", lines.stream().map(l -> l.text().trim()).toList());
            List<RectDto> rects = lines.stream().map(DocumentStructureService::rectOf).toList();
            blocks.add(new DocumentBlock(page, BlockType.PARAGRAPH, 0, text, rects));
            lines.clear();
        }
    }
}
