package com.factchecker.eval;

/** The judge's binary verdict on one case - deliberately no UNCERTAIN, per the suite's design. */
public record JudgeVerdict(boolean pass, String reasoning, FailureCategory category) {

    public static JudgeVerdict pass(String reasoning) {
        return new JudgeVerdict(true, reasoning, FailureCategory.NONE);
    }

    public static JudgeVerdict fail(String reasoning, FailureCategory category) {
        return new JudgeVerdict(false, reasoning, category);
    }
}
