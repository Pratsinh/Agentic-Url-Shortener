package com.ascs.agentic.workspace;

import java.io.IOException;
import java.nio.file.Path;

/** Runs the project's tests in a workspace. An interface so pipeline tests can avoid a real Maven build. */
@FunctionalInterface
public interface BuildRunner {

    /**
     * @param module     Maven module to test, built with its upstream modules
     * @param testFilter optional Surefire {@code -Dtest} filter, or null for all tests
     */
    TestReport runTests(Path workdir, String module, String testFilter) throws IOException, InterruptedException;
}
