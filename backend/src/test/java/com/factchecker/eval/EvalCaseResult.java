package com.factchecker.eval;

/** actualResult is a flow-specific plain-text rendering of the real response, built by each test
 *  class, so the judge and the report both see exactly what a human would see in the app. */
public record EvalCaseResult(EvalCase evalCase, String actualResult, JudgeVerdict verdict) {

    public boolean isRegression() {
        return evalCase.id().contains("regression");
    }
}
