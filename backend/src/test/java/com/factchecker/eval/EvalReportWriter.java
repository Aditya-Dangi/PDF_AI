package com.factchecker.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes the single consolidated Markdown report for a whole eval suite run. */
public final class EvalReportWriter {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private EvalReportWriter() {
    }

    public static void write(List<EvalCaseResult> results) {
        if (results.isEmpty()) {
            System.out.println("[eval] No eval cases ran - skipping report.");
            return;
        }

        try {
            Path dir = Path.of("eval-reports");
            Files.createDirectories(dir);
            Path file = dir.resolve(java.time.LocalDateTime.now().format(FILE_STAMP) + ".md");
            Files.writeString(file, render(results));
            System.out.println("[eval] Report written to " + file.toAbsolutePath());
        } catch (IOException ex) {
            System.err.println("[eval] Failed to write report: " + ex.getMessage());
        }
    }

    private static String render(List<EvalCaseResult> results) {
        long total = results.size();
        long passed = results.stream().filter(r -> r.verdict().pass()).count();
        long failed = total - passed;

        long regressionTotal = results.stream().filter(EvalCaseResult::isRegression).count();
        long regressionFailed = results.stream().filter(EvalCaseResult::isRegression).filter(r -> !r.verdict().pass()).count();
        long robustnessTotal = total - regressionTotal;
        long robustnessFailed = failed - regressionFailed;

        StringBuilder sb = new StringBuilder();
        sb.append("# Prompt Eval Report\n\n");
        sb.append("**Overall:** ").append(failed == 0 ? "PASS" : "FAIL")
                .append(" (").append(passed).append("/").append(total).append(" cases passed)\n\n");

        sb.append("## Per-flow totals\n\n");
        for (Flow flow : Flow.values()) {
            List<EvalCaseResult> flowResults = results.stream().filter(r -> r.evalCase().flow() == flow).toList();
            if (flowResults.isEmpty()) continue;
            long flowPassed = flowResults.stream().filter(r -> r.verdict().pass()).count();
            sb.append("- ").append(flow).append(": ").append(flowPassed).append("/").append(flowResults.size()).append(" passed\n");
        }

        sb.append("\n## Regression vs robustness\n\n");
        sb.append("- Regression: ").append(regressionTotal - regressionFailed).append("/").append(regressionTotal).append(" passed\n");
        sb.append("- Robustness: ").append(robustnessTotal - robustnessFailed).append("/").append(robustnessTotal).append(" passed\n");

        sb.append("\n## Cases\n");
        for (EvalCaseResult r : results) {
            sb.append("\n### ").append(r.evalCase().id()).append(" [").append(r.evalCase().flow()).append("] - ")
                    .append(r.verdict().pass() ? "PASS" : "FAIL").append("\n\n");
            sb.append("**Input:**\n```\n").append(r.evalCase().input()).append("\n```\n\n");
            sb.append("**Expectation:** ").append(r.evalCase().expectation()).append("\n\n");
            sb.append("**Actual result:**\n```\n").append(r.actualResult()).append("\n```\n\n");
            sb.append("**Judge verdict:** ").append(r.verdict().pass() ? "PASS" : "FAIL").append("\n\n");
            sb.append("**Judge reasoning:** ").append(r.verdict().reasoning()).append("\n\n");
            if (!r.verdict().pass()) {
                sb.append("**Failure category:** ").append(r.verdict().category()).append("\n\n");
            }
        }

        sb.append("\n## Failure categories\n\n```\n");
        Map<FailureCategory, Long> byCategory = results.stream()
                .filter(r -> !r.verdict().pass())
                .collect(Collectors.groupingBy(r -> r.verdict().category(), () -> new EnumMap<>(FailureCategory.class), Collectors.counting()));
        if (byCategory.isEmpty()) {
            sb.append("(no failures)\n");
        } else {
            byCategory.forEach((category, count) -> sb.append(category).append(": ").append(count).append("\n"));
        }
        sb.append("```\n");

        return sb.toString();
    }
}
