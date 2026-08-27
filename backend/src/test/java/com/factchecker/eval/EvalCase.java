package com.factchecker.eval;

/**
 * One test case. "expectation" describes required semantic behavior in plain language - never
 * exact wording, since the judge grades meaning, not phrasing.
 *
 * REGRESSION vs ROBUSTNESS is tracked by convention in "id" (e.g. "a-regression-toc-pollution" vs
 * "a-robustness-conflicting-evidence") rather than an extra field, per this suite's small model
 * (see EvalCaseResult#isRegression).
 */
public record EvalCase(String id, Flow flow, String input, String expectation) {
}
