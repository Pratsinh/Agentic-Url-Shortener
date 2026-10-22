package com.ascs.orchestrator.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** A Java source file read from the real tree, the unit that the design, review and QA agents reason about. */
public record SourceFile(String path, String content) {

    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");

    public List<String> lines() {
        return content.lines().toList();
    }

    public String packageName() {
        Matcher m = PACKAGE.matcher(content);
        return m.find() ? m.group(1) : "";
    }

    public boolean isTest() {
        return path.contains("src/test/");
    }

    /**
     * The modules of a (multi-module) project, as paths relative to its root: the root itself ({@code ""}) when it
     * has a {@code src} folder, then every direct subdirectory that has one, sorted.
     */
    public static List<String> modules(Path projectRoot) {
        List<String> out = new ArrayList<>();
        if (Files.isDirectory(projectRoot.resolve("src"))) out.add("");
        try (Stream<Path> dirs = Files.list(projectRoot)) {
            dirs.filter(d -> Files.isDirectory(d.resolve("src"))).map(d -> d.getFileName().toString()).sorted().forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** {@code src/<kind>/java} of every module, kind by kind: ("main", "test") -> all main roots, then all test roots. */
    public static String[] sourceRoots(Path projectRoot, String... kinds) {
        List<String> modules = modules(projectRoot);
        List<String> out = new ArrayList<>();
        for (String kind : kinds) {
            for (String m : modules) out.add((m.isEmpty() ? "" : m + "/") + "src/" + kind + "/java");
        }
        return out.toArray(String[]::new);
    }

    /** All {@code .java} files under the given roots (missing roots are skipped), sorted by path. */
    public static List<SourceFile> scan(Path projectRoot, String... roots) {
        List<SourceFile> out = new ArrayList<>();
        for (String root : roots) {
            Path dir = projectRoot.resolve(root);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String rel = projectRoot.relativize(p).toString().replace('\\', '/');
                    out.add(new SourceFile(rel, Files.readString(p)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }
}
