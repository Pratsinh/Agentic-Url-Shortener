package com.ascs.orchestrator.planner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Brownfield reasoning on the real source tree: builds a class-reference graph by textual reference
 * (approximate: comments and string literals are ignored, but reflection/DI wiring is not understood) and walks it backwards from the changed symbols.
 */
public final class CodebaseImpactAnalyzer {

    public record Report(List<String> changed, List<String> direct, List<String> transitive) {
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder("# Impact analysis\n\n**Changed symbols:** " + changed + "\n\n");
            sb.append("## Directly impacted (reference a changed symbol)\n");
            direct.forEach(d -> sb.append("- ").append(d).append('\n'));
            sb.append("\n## Transitively impacted (depend on a directly impacted class)\n");
            if (transitive.isEmpty()) sb.append("- none\n");
            transitive.forEach(d -> sb.append("- ").append(d).append('\n'));
            return sb.toString();
        }
    }

    private final List<Path> roots;

    public CodebaseImpactAnalyzer(Path... roots) {
        this.roots = List.of(roots);
    }

    public boolean available() {
        return roots.stream().anyMatch(Files::isDirectory);
    }

    public Report analyze(String... changedSymbols) {
        Map<String, Path> files = new TreeMap<>();
        Map<String, String> sources = new TreeMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    String name = p.getFileName().toString().replaceFirst("\\.java$", "");
                    files.put(name, p);
                    sources.put(name, stripCommentsAndStrings(Files.readString(p)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, Set<String>> referencedBy = new TreeMap<>(); // class -> classes that reference it
        for (String target : files.keySet()) {
            Pattern word = Pattern.compile("\\b" + Pattern.quote(target) + "\\b");
            for (Map.Entry<String, String> src : sources.entrySet()) {
                if (!src.getKey().equals(target) && word.matcher(src.getValue()).find()) {
                    referencedBy.computeIfAbsent(target, k -> new TreeSet<>()).add(src.getKey());
                }
            }
        }
        Set<String> changed = new LinkedHashSet<>(List.of(changedSymbols));
        Set<String> direct = new TreeSet<>();
        for (String c : changed) direct.addAll(referencedBy.getOrDefault(c, Set.of()));
        direct.removeAll(changed);

        Set<String> transitive = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>(direct);
        Set<String> seen = new LinkedHashSet<>(direct);
        seen.addAll(changed);
        while (!queue.isEmpty()) {
            for (String dependent : referencedBy.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(dependent)) { transitive.add(dependent); queue.add(dependent); }
            }
        }
        return new Report(List.copyOf(changed), direct.stream().map(n -> describe(n, files)).toList(),
                transitive.stream().map(n -> describe(n, files)).toList());
    }

    private static final Pattern NOISE = Pattern.compile(
            "/\\*.*?\\*/|//[^\\n]*|\"(\\\\.|[^\"\\\\\\n])*\"", Pattern.DOTALL);

    /** References inside comments and string literals are not code dependencies. */
    static String stripCommentsAndStrings(String source) {
        return NOISE.matcher(source).replaceAll(" ");
    }

    private static String describe(String name, Map<String, Path> files) {
        Path p = files.get(name);
        boolean test = p.toString().replace('\\', '/').contains("/test/");
        return name + (test ? " (test)" : "") + "  [" + p + "]";
    }
}
