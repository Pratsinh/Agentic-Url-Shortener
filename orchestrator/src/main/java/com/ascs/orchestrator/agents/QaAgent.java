package com.ascs.orchestrator.agents;

import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.StageInput;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA agent: reports unit-test coverage (from the JaCoCo XML report written by {@code mvn verify}) and functional
 * coverage (every acceptance criterion from the user stories must be claimed by at least one test through
 * {@code @Verifies}), compares both with the target and lists every place the target was not achieved.
 */
public final class QaAgent implements Agent {

    public static final String OUTPUT = "qa-report.md";
    private static final Pattern AC_ROW = Pattern.compile("(?m)^\\| (AC-\\d+\\.\\d+) \\|");
    private static final Pattern AC_ID = Pattern.compile("AC-\\d+\\.\\d+");
    private static final Pattern VERIFIES = Pattern.compile("@Verifies\\(([^)]*)\\)");
    private static final Pattern METHOD = Pattern.compile("void\\s+(\\w+)\\s*\\(");
    private static final Pattern TEST = Pattern.compile("@(Test|ParameterizedTest)\\b");

    /** missed/covered pair of one JaCoCo counter. */
    public record Counter(long missed, long covered) {
        public double percent() {
            long total = missed + covered;
            return total == 0 ? 100.0 : 100.0 * covered / total;
        }
        String format() { return String.format(Locale.ROOT, "%.1f%% (%d/%d)", percent(), covered, missed + covered); }
    }

    /** Coverage of one source file plus the exact lines that were missed or only partly branched. */
    public record FileCoverage(String file, Counter lines, Counter branches, List<Integer> missedLines, List<Integer> partialBranches) {}

    public record Coverage(Map<String, Counter> totals, Map<String, Map<String, Counter>> packages, List<FileCoverage> files) {}

    private final Path projectRoot;
    private final List<Path> jacocoXmls;
    private final List<Path> testResults;
    private final double target;

    /** Without test results, criteria are traced to the tests that declare them, not to executions. */
    public QaAgent(Path projectRoot, Path jacocoXml, double target) {
        this(projectRoot, List.of(jacocoXml), List.of(), target);
    }

    /** @param testResults surefire report directory: only tests that ran and passed verify a criterion */
    public QaAgent(Path projectRoot, Path jacocoXml, Path testResults, double target) {
        this(projectRoot, List.of(jacocoXml), List.of(testResults), target);
    }

    /**
     * @param jacocoXmls  one JaCoCo report per module; missing ones are skipped, the others are merged
     * @param testResults one surefire report directory per module
     */
    public QaAgent(Path projectRoot, List<Path> jacocoXmls, List<Path> testResults, double target) {
        this.projectRoot = projectRoot;
        this.jacocoXmls = List.copyOf(jacocoXmls);
        this.testResults = List.copyOf(testResults);
        this.target = target;
    }

    @Override
    public AgentResult execute(StageInput input) throws Exception {
        List<SourceFile> tests = SourceFile.scan(projectRoot, SourceFile.sourceRoots(projectRoot, "test"));
        Coverage cov = null;
        for (Path xml : jacocoXmls) {
            if (Files.isRegularFile(xml)) cov = merge(cov, parse(xml));
        }
        Map<String, String> outcomes = testOutcomes(testResults); // null when tests were not run

        List<String> criteria = new ArrayList<>();
        input.artifact(RequirementsAgent.OUTPUT).map(Artifact::content).ifPresent(c -> {
            Matcher m = AC_ROW.matcher(c);
            while (m.find()) criteria.add(m.group(1));
        });
        Map<String, List<String>> verifiedBy = new TreeMap<>();
        criteria.forEach(c -> verifiedBy.put(c, new ArrayList<>()));
        List<String> unknown = new ArrayList<>();
        Map<String, Integer> testCounts = new LinkedHashMap<>();
        for (SourceFile t : tests) {
            String cls = t.path().substring(t.path().lastIndexOf('/') + 1).replace(".java", "");
            int n = (int) TEST.matcher(t.content()).results().count();
            if (n > 0) testCounts.put(cls, n);
            Matcher v = VERIFIES.matcher(t.content());
            while (v.find()) {
                Matcher name = METHOD.matcher(t.content());
                String test = cls + "#" + (name.find(v.end()) ? name.group(1) : "?");
                Matcher ids = AC_ID.matcher(v.group(1));
                while (ids.find()) {
                    List<String> list = verifiedBy.get(ids.group());
                    if (list != null) list.add(test);
                    else unknown.add(ids.group() + " in " + test);
                }
            }
        }
        java.util.function.Predicate<String> counts = t -> outcomes == null || "passed".equals(outcomes.get(t));
        long verified = verifiedBy.values().stream().filter(l -> l.stream().anyMatch(counts)).count();
        Counter functional = new Counter(criteria.size() - verified, verified);
        int totalTests = testCounts.values().stream().mapToInt(Integer::intValue).sum();

        StringBuilder md = new StringBuilder("# QA report\n\n")
                .append("Generated by `QaAgent`. Target: **").append(fmt(target)).append("%** line, branch and method coverage, ")
                .append("and every acceptance criterion verified by at least one test.\n\n");
        md.append("## 1. Summary\n\n| Measure | Result | Target | Met |\n|---|---|---|---|\n")
                .append("| Unit tests | ").append(totalTests).append(" tests in ").append(testCounts.size()).append(" classes | - | - |\n");
        List<String> unmet = new ArrayList<>();
        for (String type : List.of("LINE", "BRANCH", "METHOD")) {
            String label = type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT) + " coverage";
            if (cov == null) {
                md.append("| ").append(label).append(" | not measured | ").append(fmt(target)).append("% | no |\n");
                unmet.add(label);
                continue;
            }
            Counter c = cov.totals().getOrDefault(type, new Counter(0, 0));
            boolean met = c.percent() >= target;
            if (!met) unmet.add(label);
            md.append("| ").append(label).append(" | ").append(c.format()).append(" | ").append(fmt(target))
                    .append("% | ").append(met ? "yes" : "**no**").append(" |\n");
        }
        boolean functionalMet = functional.missed() == 0 && !criteria.isEmpty();
        if (!functionalMet) unmet.add("Functional coverage");
        md.append("| Functional coverage (acceptance criteria) | ").append(functional.format()).append(" | 100% | ")
                .append(functionalMet ? "yes" : "**no**").append(" |\n\n");

        md.append("## 2. Unit test inventory\n\n| Test class | Tests |\n|---|---|\n");
        testCounts.forEach((c, n) -> md.append("| ").append(c).append(" | ").append(n).append(" |\n"));

        md.append("\n## 3. Unit coverage by package\n\n");
        if (cov == null) {
            md.append("No JaCoCo report at ").append(String.join(", ", jacocoXmls.stream().map(p -> "`" + p + "`").toList()))
                    .append(": run `mvn verify` first.\n");
        } else {
            md.append("| Package | Lines | Branches | Methods |\n|---|---|---|---|\n");
            cov.packages().forEach((p, cs) -> md.append("| `").append(p).append("` | ")
                    .append(cs.getOrDefault("LINE", new Counter(0, 0)).format()).append(" | ")
                    .append(cs.getOrDefault("BRANCH", new Counter(0, 0)).format()).append(" | ")
                    .append(cs.getOrDefault("METHOD", new Counter(0, 0)).format()).append(" |\n"));
        }

        md.append("\n## 4. Functional coverage: acceptance criterion -> tests\n\n");
        md.append(outcomes == null ? "Test results not found: criteria are traced to the tests that declare them, not to executions.\n\n"
                : "Only tests that ran and passed count; skipped, failed or missing tests are marked.\n\n");
        if (criteria.isEmpty()) md.append("No acceptance criteria were supplied upstream.\n");
        else md.append("| Criterion | Verified by |\n|---|---|\n");
        verifiedBy.forEach((ac, ts) -> md.append("| ").append(ac).append(" | ")
                .append(ts.isEmpty() ? "**none**" : String.join("<br>", ts.stream()
                        .map(t -> counts.test(t) ? t : t + " (" + outcomes.getOrDefault(t, "not run") + ")").toList()))
                .append(" |\n"));

        md.append("\n## 5. Gaps: where the target was not achieved\n\n");
        int gaps = 0;
        if (cov != null) {
            for (FileCoverage f : cov.files()) {
                if (f.lines().percent() >= target && f.branches().percent() >= target) continue;
                if (gaps++ == 0) md.append("| Source file | Lines | Branches | Missed lines | Partly covered branches |\n|---|---|---|---|---|\n");
                md.append("| `").append(f.file()).append("` | ").append(f.lines().format()).append(" | ")
                        .append(f.branches().format()).append(" | ").append(ranges(f.missedLines())).append(" | ")
                        .append(ranges(f.partialBranches())).append(" |\n");
            }
        }
        for (Map.Entry<String, List<String>> e : verifiedBy.entrySet()) {
            if (e.getValue().stream().noneMatch(counts)) {
                md.append("- Acceptance criterion ").append(e.getKey()).append(e.getValue().isEmpty() ? " has no verifying test.\n"
                        : " has no passing test (declared by " + String.join(", ", e.getValue()) + ").\n");
                gaps++;
            }
        }
        for (String u : unknown) { md.append("- Traceability error: ").append(u).append(" references an unknown criterion.\n"); gaps++; }
        if (cov == null) { md.append("- Unit coverage was not measured.\n"); gaps++; }
        if (gaps == 0) md.append("None: every target was met.\n");

        return new AgentResult(Map.of(OUTPUT, md.toString()), unmet.isEmpty() ? "all coverage targets met"
                : "target not met for: " + String.join(", ", unmet) + " (" + gaps + " gap(s) listed)");
    }

    /** Parses a JaCoCo XML report without fetching its DTD. */
    public static Coverage parse(Path xml) throws Exception {
        Document doc = xml(xml);
        Element report = doc.getDocumentElement();

        Map<String, Map<String, Counter>> packages = new TreeMap<>();
        List<FileCoverage> files = new ArrayList<>();
        for (Element pkg : children(report, "package")) {
            String pkgName = pkg.getAttribute("name").replace('/', '.');
            packages.put(pkgName, counters(pkg));
            for (Element sf : children(pkg, "sourcefile")) {
                List<Integer> missed = new ArrayList<>();
                List<Integer> partial = new ArrayList<>();
                for (Element line : children(sf, "line")) {
                    int nr = Integer.parseInt(line.getAttribute("nr"));
                    if (Integer.parseInt(line.getAttribute("mi")) > 0) missed.add(nr);
                    else if (Integer.parseInt(line.getAttribute("mb")) > 0) partial.add(nr);
                }
                Map<String, Counter> c = counters(sf);
                files.add(new FileCoverage(pkg.getAttribute("name") + "/" + sf.getAttribute("name"),
                        c.getOrDefault("LINE", new Counter(0, 0)), c.getOrDefault("BRANCH", new Counter(0, 0)), missed, partial));
            }
        }
        return new Coverage(counters(report), packages, files);
    }

    /** Two reports over disjoint classes (two modules) as one; {@code a} may be null. */
    static Coverage merge(Coverage a, Coverage b) {
        if (a == null) return b;
        Map<String, Counter> totals = new LinkedHashMap<>(a.totals());
        b.totals().forEach((type, c) -> totals.merge(type, c, (x, y) -> new Counter(x.missed() + y.missed(), x.covered() + y.covered())));
        Map<String, Map<String, Counter>> packages = new TreeMap<>(a.packages());
        packages.putAll(b.packages());
        List<FileCoverage> files = new ArrayList<>(a.files());
        files.addAll(b.files());
        return new Coverage(totals, packages, files);
    }

    /** Secure DOM parse: no DTD fetching, no entity expansion. */
    private static Document xml(Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(file.toFile());
    }

    private static final List<String> SEVERITY = List.of("passed", "skipped", "failed");

    /**
     * "TestClass#method" -> passed | skipped | failed, from surefire XML reports; null if there are none.
     * Repeated invocations (parameterized tests) keep the worst outcome.
     */
    static Map<String, String> testOutcomes(List<Path> dirs) throws Exception {
        Map<String, String> out = new java.util.HashMap<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                    for (Element tc : children(xml(file).getDocumentElement(), "testcase")) {
                        String cls = tc.getAttribute("classname");
                        String test = cls.substring(cls.lastIndexOf('.') + 1) + "#" + tc.getAttribute("name").replaceFirst("[(\\[].*$", "");
                        String status = !children(tc, "skipped").isEmpty() ? "skipped"
                                : children(tc, "failure").isEmpty() && children(tc, "error").isEmpty() ? "passed" : "failed";
                        out.merge(test, status, (a, b) -> SEVERITY.indexOf(a) >= SEVERITY.indexOf(b) ? a : b);
                    }
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Counter> counters(Element parent) {
        Map<String, Counter> out = new LinkedHashMap<>();
        for (Element c : children(parent, "counter")) {
            out.put(c.getAttribute("type"), new Counter(Long.parseLong(c.getAttribute("missed")), Long.parseLong(c.getAttribute("covered"))));
        }
        return out;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) out.add(e);
        }
        return out;
    }

    /** [3,4,5,9] -> "3-5, 9" */
    static String ranges(List<Integer> lines) {
        if (lines.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        int start = lines.get(0), prev = start;
        for (int i = 1; i <= lines.size(); i++) {
            Integer cur = i < lines.size() ? lines.get(i) : null;
            if (cur != null && cur == prev + 1) { prev = cur; continue; }
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(start == prev ? String.valueOf(start) : start + "-" + prev);
            if (cur != null) { start = cur; prev = cur; }
        }
        return sb.toString();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
