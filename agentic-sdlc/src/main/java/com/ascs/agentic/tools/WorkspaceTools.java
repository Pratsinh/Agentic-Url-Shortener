package com.ascs.agentic.tools;

import com.ascs.agentic.workspace.BuildRunner;
import com.ascs.agentic.workspace.GitWorkspace;
import com.ascs.agentic.workspace.TestReport;
import com.ascs.orchestrator.tools.Risk;
import com.ascs.orchestrator.tools.Tool;
import com.ascs.orchestrator.tools.ToolOutcome;
import com.ascs.orchestrator.tools.ToolParam;
import com.ascs.orchestrator.tools.ToolSpec;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * The repository tools agents may be granted, all confined to one run's git worktree by {@link PathGuard}.
 * Results use HTTP-style status codes (400 bad request, 404 missing, 409 ambiguous edit) that the gateway passes
 * back to the model as errors it can act on. Which stage gets which tool, and where it may write, is decided by
 * the gateway's grants and {@link WorkspaceWritePolicy}, not here.
 */
public final class WorkspaceTools {

    public static final String LIST = "list_files", READ = "read_file", SEARCH = "search_code", WRITE = "write_file",
            REPLACE = "replace_in_file", TEST = "run_tests", DIFF = "git_diff";
    public static final List<String> READ_TOOLS = List.of(LIST, READ, SEARCH, DIFF);
    public static final List<String> WRITE_TOOLS = List.of(WRITE, REPLACE, TEST);

    static final Set<String> SKIP_DIRS = Set.of(".git", "target", "node_modules", ".idea", ".agentic");
    static final Set<String> TEXT_EXT = Set.of("java", "xml", "yaml", "yml", "md", "properties", "json", "txt", "sql", "cql", "sh");
    private static final int MAX_LIST = 400, MAX_READ_CHARS = 100_000, MAX_MATCHES = 150, MAX_WRITE_CHARS = 200_000;

    private final PathGuard guard;
    private final GitWorkspace workspace;
    private final BuildRunner build;
    private final Set<String> testModules;

    public WorkspaceTools(GitWorkspace workspace, BuildRunner build, Set<String> testModules) throws IOException {
        this.workspace = workspace;
        this.guard = new PathGuard(workspace.root());
        this.build = build;
        this.testModules = Set.copyOf(testModules);
    }

    public List<Tool> all() {
        return List.of(listFiles(), readFile(), searchCode(), writeFile(), replaceInFile(), runTests(), gitDiff());
    }

    private static Tool tool(ToolSpec spec, Body body) {
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ToolOutcome invoke(Map<String, String> args, String correlationId) {
                try {
                    return body.run(args);
                } catch (IllegalArgumentException e) {
                    return new ToolOutcome(400, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new ToolOutcome(503, "interrupted");
                } catch (IOException e) {
                    return new ToolOutcome(500, "I/O error: " + e.getMessage());
                }
            }
        };
    }

    @FunctionalInterface
    private interface Body {
        ToolOutcome run(Map<String, String> args) throws IOException, InterruptedException;
    }

    Tool listFiles() {
        return tool(new ToolSpec(LIST, "List files (recursively) under a directory of the repository, skipping build output.",
                Risk.READ_ONLY, List.of(ToolParam.optional("path", "string", "directory relative to the repository root; default '.'"))), args -> {
            Path dir = guard.resolve(args.getOrDefault("path", "."));
            if (!Files.isDirectory(dir)) return new ToolOutcome(404, "not a directory: " + args.getOrDefault("path", "."));
            List<String> files = new ArrayList<>();
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(Files::isRegularFile).filter(p -> !skipped(p)).sorted().limit(MAX_LIST + 1L)
                        .forEach(p -> files.add(guard.relative(p)));
            }
            String body = String.join("\n", files.subList(0, Math.min(files.size(), MAX_LIST)));
            return new ToolOutcome(200, files.size() > MAX_LIST ? body + "\n...[more files; narrow the path]" : body);
        });
    }

    Tool readFile() {
        return tool(new ToolSpec(READ, "Read a text file with line numbers. Optionally a 1-based inclusive line range.",
                Risk.READ_ONLY, List.of(ToolParam.required("path", "string", "file path relative to the repository root"),
                ToolParam.optional("start_line", "integer", "first line (1-based)"),
                ToolParam.optional("end_line", "integer", "last line (inclusive)"))), args -> {
            Path file = guard.resolve(args.get("path"));
            if (!Files.isRegularFile(file)) return new ToolOutcome(404, "no such file: " + args.get("path"));
            List<String> lines = readLines(file);
            int from = Math.max(1, Integer.parseInt(args.getOrDefault("start_line", "1")));
            int to = Math.min(lines.size(), Integer.parseInt(args.getOrDefault("end_line", String.valueOf(lines.size()))));
            StringBuilder sb = new StringBuilder();
            for (int i = from; i <= to; i++) {
                sb.append(i).append(": ").append(lines.get(i - 1)).append('\n');
                if (sb.length() > MAX_READ_CHARS) {
                    sb.append("...[truncated at line ").append(i).append(" of ").append(lines.size()).append("; read a range]");
                    break;
                }
            }
            return new ToolOutcome(200, lines.isEmpty() ? "(empty file)" : sb.toString());
        });
    }

    Tool searchCode() {
        return tool(new ToolSpec(SEARCH, "Search text files with a Java regular expression; returns path:line: text matches.",
                Risk.READ_ONLY, List.of(ToolParam.required("pattern", "string", "Java regex"),
                ToolParam.optional("path", "string", "directory to search; default '.'"))), args -> {
            Pattern pattern;
            try {
                pattern = Pattern.compile(args.get("pattern"));
            } catch (PatternSyntaxException e) {
                return new ToolOutcome(400, "invalid regex: " + e.getDescription());
            }
            Path dir = guard.resolve(args.getOrDefault("path", "."));
            List<String> hits = new ArrayList<>();
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.filter(Files::isRegularFile).filter(p -> !skipped(p)).filter(WorkspaceTools::isText).sorted().toList()) {
                    List<String> lines = readLines(p);
                    for (int i = 0; i < lines.size() && hits.size() <= MAX_MATCHES; i++) {
                        Matcher m = pattern.matcher(lines.get(i));
                        if (m.find()) hits.add(guard.relative(p) + ":" + (i + 1) + ": " + abbreviate(lines.get(i).strip()));
                    }
                    if (hits.size() > MAX_MATCHES) break;
                }
            }
            if (hits.isEmpty()) return new ToolOutcome(200, "no matches");
            return new ToolOutcome(200, String.join("\n", hits.subList(0, Math.min(hits.size(), MAX_MATCHES)))
                    + (hits.size() > MAX_MATCHES ? "\n...[more matches; refine the pattern]" : ""));
        });
    }

    Tool writeFile() {
        return tool(new ToolSpec(WRITE, "Create or overwrite a text file in the run workspace with the complete content.",
                Risk.WORKSPACE_WRITE, List.of(ToolParam.required("path", "string", "file path relative to the repository root"),
                ToolParam.required("content", "string", "the complete new file content"))), args -> {
            String content = args.get("content");
            if (content.length() > MAX_WRITE_CHARS) return new ToolOutcome(400, "content exceeds " + MAX_WRITE_CHARS + " characters");
            Path file = guard.resolve(args.get("path"));
            if (Files.isDirectory(file)) return new ToolOutcome(400, "path is a directory");
            boolean existed = Files.exists(file);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return new ToolOutcome(200, (existed ? "overwrote " : "created ") + guard.relative(file) + " ("
                    + content.lines().count() + " lines)");
        });
    }

    Tool replaceInFile() {
        return tool(new ToolSpec(REPLACE, "Replace one exact occurrence of old_text with new_text in a file. old_text must "
                + "match exactly once (include enough surrounding lines to make it unique).", Risk.WORKSPACE_WRITE, List.of(
                ToolParam.required("path", "string", "file path relative to the repository root"),
                ToolParam.required("old_text", "string", "exact text to replace, including whitespace"),
                ToolParam.required("new_text", "string", "replacement text"))), args -> {
            Path file = guard.resolve(args.get("path"));
            if (!Files.isRegularFile(file)) return new ToolOutcome(404, "no such file: " + args.get("path"));
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String old = args.get("old_text");
            if (old.isEmpty()) return new ToolOutcome(400, "old_text must not be empty");
            int count = occurrences(text, old);
            if (count != 1) {
                return new ToolOutcome(count == 0 ? 404 : 409, "old_text found " + count + " times in " + args.get("path")
                        + (count == 0 ? "; read the file again and copy the text exactly" : "; add surrounding lines to make it unique"));
            }
            Files.writeString(file, text.replace(old, args.get("new_text")), StandardCharsets.UTF_8);
            return new ToolOutcome(200, "replaced 1 occurrence in " + guard.relative(file));
        });
    }

    Tool runTests() {
        return tool(new ToolSpec(TEST, "Compile and run the tests of a module (Maven, offline) in the run workspace. "
                + "Optionally restrict to test classes/methods, e.g. 'ShortenerServiceTest' or 'ShortenerApiTest#create*'.",
                Risk.WORKSPACE_WRITE, List.of(ToolParam.required("module", "string", "one of " + testModules),
                ToolParam.optional("tests", "string", "Surefire -Dtest filter"))), args -> {
            String module = args.get("module");
            if (!testModules.contains(module)) return new ToolOutcome(400, "module must be one of " + testModules);
            TestReport r = build.runTests(workspace.root(), module, args.get("tests"));
            StringBuilder sb = new StringBuilder(r.summary()).append('\n');
            r.failedTests().forEach(f -> sb.append("FAILED ").append(f).append('\n'));
            if (!r.passed()) sb.append("--- build output (tail) ---\n").append(r.outputTail());
            return new ToolOutcome(200, sb.toString());
        });
    }

    Tool gitDiff() {
        return tool(new ToolSpec(DIFF, "Show the unified diff of all changes made in this run so far (against the base commit).",
                Risk.READ_ONLY, List.of()), args -> {
            String diff = workspace.diff();
            return new ToolOutcome(200, diff.isBlank() ? "(no changes yet)" : diff);
        });
    }

    /** Build output, VCS and system directories, judged on the path relative to the workspace root. */
    private boolean skipped(Path p) {
        for (Path part : guard.root().relativize(p.toAbsolutePath().normalize())) if (SKIP_DIRS.contains(part.toString())) return true;
        return false;
    }

    private static boolean isText(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && TEXT_EXT.contains(n.substring(dot + 1));
    }

    private static List<String> readLines(Path p) throws IOException {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IllegalArgumentException("not a UTF-8 text file: " + p.getFileName());
        }
    }

    static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
