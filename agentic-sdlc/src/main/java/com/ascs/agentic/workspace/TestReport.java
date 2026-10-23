package com.ascs.agentic.workspace;

import java.util.List;

/**
 * Result of a real build: the exit code and output of the Maven process and the counts parsed from the Surefire
 * XML reports it wrote. Nothing here is self-reported by a model.
 */
public record TestReport(String command, int exitCode, boolean timedOut, long durationMs, int tests, int failures,
                         int errors, int skipped, List<String> failedTests, Double lineCoverage, String outputTail) {

    public TestReport {
        failedTests = List.copyOf(failedTests);
    }

    public boolean passed() {
        return exitCode == 0 && !timedOut && failures == 0 && errors == 0 && tests > 0;
    }

    public String summary() {
        return (passed() ? "PASSED" : "FAILED") + ": tests=" + tests + " failures=" + failures + " errors=" + errors
                + " skipped=" + skipped + (lineCoverage == null ? "" : String.format(java.util.Locale.ROOT, " lineCoverage=%.1f%%", lineCoverage * 100)) + " exit=" + exitCode + (timedOut ? " (timed out)" : "") + " in " + durationMs + " ms";
    }
}
