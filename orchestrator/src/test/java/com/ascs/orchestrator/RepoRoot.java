package com.ascs.orchestrator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The multi-module project root that the agents and scenarios work on. Tests run with the orchestrator module as
 * working directory (Maven, IDEs) or from the root (scripts), so it is found by walking up to the shortener module.
 */
public final class RepoRoot {
    private RepoRoot() {}

    public static final Path PATH = find();

    private static Path find() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("shortener-service")) && Files.isDirectory(p.resolve("orchestrator"))) return p;
        }
        throw new IllegalStateException("not inside the project: no directory with shortener-service and orchestrator");
    }
}
