package com.factchecker.eval;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared across all 3 flow test classes in a single suite run, so one report covers all of them. */
public final class EvalResultsCollector {

    private static final List<EvalCaseResult> RESULTS = new CopyOnWriteArrayList<>();

    private EvalResultsCollector() {
    }

    /** Records a result and prints one-line progress immediately, so a long run shows live progress. */
    public static void record(EvalCaseResult result) {
        RESULTS.add(result);
        String status = result.verdict().pass() ? "PASS" : "FAIL";
        System.out.printf("[eval] %-9s %-45s %s%n", status, result.evalCase().id(), result.evalCase().flow());
    }

    public static List<EvalCaseResult> all() {
        return List.copyOf(RESULTS);
    }
}
