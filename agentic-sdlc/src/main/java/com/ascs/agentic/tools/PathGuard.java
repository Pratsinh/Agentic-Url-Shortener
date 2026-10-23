package com.ascs.agentic.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Confines model-supplied paths to the workspace: relative paths only, no {@code ..}, no {@code .git}, and the
 * resolved real path (symlinks followed) must stay under the workspace root.
 */
public final class PathGuard {

    private final Path root;
    private final Path realRoot;

    public PathGuard(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.realRoot = this.root.toRealPath();
    }

    /** Normalized forward-slash form of a model-supplied relative path, or an exception explaining the refusal. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("path is required");
        String p = raw.strip().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*") || p.contains("\u0000")) {
            throw new IllegalArgumentException("path must be relative to the repository root");
        }
        for (String seg : p.split("/")) {
            if (seg.equals("..")) throw new IllegalArgumentException("path must not contain '..'");
            if (seg.toLowerCase(Locale.ROOT).equals(".git")) throw new IllegalArgumentException("the .git directory is off limits");
        }
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "." : p;
    }

    public Path resolve(String raw) throws IOException {
        Path p = root.resolve(normalize(raw)).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("path escapes the workspace");
        Path existing = p;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
            throw new IllegalArgumentException("path escapes the workspace (symlink)");
        }
        return p;
    }

    public String relative(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public Path root() {
        return root;
    }
}
