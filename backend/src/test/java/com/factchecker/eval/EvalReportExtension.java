package com.factchecker.eval;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Standard JUnit5 "singleton extension" pattern: registering this instance in the ROOT context
 * store means its close() runs exactly once, after every eval test class in the run has finished -
 * so 3 test classes (Flow A/B/C) still produce a single consolidated report, not three.
 */
public class EvalReportExtension implements BeforeAllCallback {

    private static final Object LOCK = new Object();
    private static boolean registered = false;

    @Override
    public void beforeAll(ExtensionContext context) {
        synchronized (LOCK) {
            if (!registered) {
                registered = true;
                context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                        .put(EvalReportExtension.class, (ExtensionContext.Store.CloseableResource) this::onSuiteFinished);
            }
        }
    }

    private void onSuiteFinished() {
        EvalReportWriter.write(EvalResultsCollector.all());
    }
}
