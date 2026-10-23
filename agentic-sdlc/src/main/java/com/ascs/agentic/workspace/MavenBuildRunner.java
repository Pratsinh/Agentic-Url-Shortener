package com.ascs.agentic.workspace;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The only build an agent can trigger: {@code mvn -B -o -pl <module> -am test} with an allowlisted module and an
 * optional, pattern-checked test filter. Offline mode ({@code -o}) means agent-written code cannot pull new
 * dependencies; the environment is scrubbed of credentials ({@link Commands}). Runs are serialized per instance
 * because concurrent builds in one worktree would overwrite each other's {@code target/} output.
 *
 * <p>Counts come from the Surefire XML reports written by this run (files older than its start are ignored) and
 * line coverage from the JaCoCo CSV, parsed with external entities disabled.
 */
public final class MavenBuildRunner implements BuildRunner {

    static final Pattern TEST_FILTER = Pattern.compile("[A-Za-z0-9_#*.,!+\\-]{1,200}");
    private static final int TAIL = 8_000;

    private final String mvn;
    private final Set<String> modules;
    private final Duration timeout;

    public MavenBuildRunner(String mvn, Set<String> modules, Duration timeout) {
        this.mvn = mvn;
        this.modules = Set.copyOf(modules);
        this.timeout = timeout;
    }

    @Override
    public synchronized TestReport runTests(Path workdir, String module, String testFilter) throws IOException, InterruptedException {
        if (!modules.contains(module)) throw new IllegalArgumentException("module not allowed: " + module);
        if (testFilter != null && !TEST_FILTER.matcher(testFilter).matches()) throw new IllegalArgumentException("invalid test filter");
        List<String> cmd = new ArrayList<>(List.of(mvn, "-B", "-o", "-ntp", "-Dstyle.color=never", "-pl", module, "-am", "test",
                "-Dsurefire.failIfNoSpecifiedTests=false"));
        if (testFilter != null) cmd.add("-Dtest=" + testFilter);
        long startMillis = System.currentTimeMillis() - 1_000;
        Commands.Result r = Commands.run(cmd, workdir, timeout, Map.of("JAVA_HOME", System.getProperty("java.home"),
                "MAVEN_OPTS", "-Xmx1g"));
        Counts c = surefire(workdir, startMillis);
        String out = r.output();
        return new TestReport(String.join(" ", cmd.subList(1, cmd.size())), r.exitCode(), r.timedOut(), r.durationMs(),
                c.tests, c.failures, c.errors, c.skipped, c.failed, coverage(workdir.resolve(module), startMillis),
                out.length() > TAIL ? out.substring(out.length() - TAIL) : out);
    }

    private static final class Counts {
        int tests, failures, errors, skipped;
        final List<String> failed = new ArrayList<>();
    }

    static Counts surefire(Path workdir, long sinceMillis) throws IOException {
        Counts c = new Counts();
        List<Path> reports;
        try (Stream<Path> s = Files.walk(workdir, 4)) {
            reports = s.filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("surefire-reports"))
                    .filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
                    .filter(p -> p.toFile().lastModified() >= sinceMillis)
                    .sorted().toList();
        }
        for (Path p : reports) {
            Element suite = parse(p).getDocumentElement();
            c.tests += intAttr(suite, "tests");
            c.failures += intAttr(suite, "failures");
            c.errors += intAttr(suite, "errors");
            c.skipped += intAttr(suite, "skipped");
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                for (String kind : List.of("failure", "error")) {
                    NodeList bad = tc.getElementsByTagName(kind);
                    if (bad.getLength() > 0) {
                        String msg = ((Element) bad.item(0)).getAttribute("message");
                        c.failed.add(tc.getAttribute("classname") + "#" + tc.getAttribute("name") + " [" + kind + "] "
                                + (msg.length() > 300 ? msg.substring(0, 300) + "..." : msg));
                    }
                }
            }
        }
        return c;
    }

    /** Line coverage of the module from {@code target/site/jacoco/jacoco.csv}, when this run wrote it. */
    static Double coverage(Path moduleDir, long sinceMillis) throws IOException {
        Path csv = moduleDir.resolve("target/site/jacoco/jacoco.csv");
        if (!Files.isRegularFile(csv) || csv.toFile().lastModified() < sinceMillis) return null;
        long missed = 0, covered = 0;
        List<String> lines = Files.readAllLines(csv);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            if (f.length < 9) continue;
            missed += Long.parseLong(f[7]);
            covered += Long.parseLong(f[8]);
        }
        return missed + covered == 0 ? null : (double) covered / (missed + covered);
    }

    private static Document parse(Path xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(xml.toFile());
        } catch (Exception e) {
            throw new IOException("unreadable surefire report " + xml.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static int intAttr(Element e, String name) {
        String v = e.getAttribute(name);
        return v.isBlank() ? 0 : (int) Double.parseDouble(v);
    }
}
