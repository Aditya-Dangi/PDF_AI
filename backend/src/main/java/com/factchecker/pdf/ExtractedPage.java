package com.factchecker.pdf;

import java.util.List;

public record ExtractedPage(
        int pageNumber,
        double width,
        double height,
        List<ExtractedLine> lines
) {
}
