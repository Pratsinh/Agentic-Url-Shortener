package com.ascs.orchestrator;

import com.ascs.Verifies;
import com.ascs.orchestrator.agents.CodeReviewAgent;
import com.ascs.orchestrator.agents.CommandRunner;
import com.ascs.orchestrator.agents.DeliveryPipeline;
import com.ascs.orchestrator.agents.DesignAgent;
import com.ascs.orchestrator.agents.DevelopmentAgent;
import com.ascs.orchestrator.agents.QaAgent;
import com.ascs.orchestrator.agents.ReleaseReadinessAgent;
import com.ascs.orchestrator.agents.RequirementsAgent;
import com.ascs.orchestrator.agents.SourceFile;
import com.ascs.orchestrator.agents.StoryCatalog;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.StageInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The role agents. Fixture sources are assembled from fragments ("@" + "Test", "AKIA" + ...) because the
 * review and QA agents also scan this very file when they run against the real tree.
 */
class AgentsTest {

    private static final Path REPO = RepoRoot.PATH;

    static Artifact artifact(String name, String content) {
        return new Artifact(name, 1, content, "upstream", List.of(), "hash", Instant.EPOCH);
    }

    static StageInput input(String requirement, Artifact... upstream) {
        return new StageInput("run", "stage", requirement, List.of(upstream), 1);
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static String stories(String requirement) {
        return new RequirementsAgent().execute(input(requirement)).outputs().get(RequirementsAgent.OUTPUT);
    }

    // ------------------------------------------------------------------ requirements

    @Test
    @Verifies({"AC-8.1"})
    void requirementsAgentWritesStoriesWithGivenWhenThenCriteria() {
        var result = new RequirementsAgent().execute(input("Build a URL shortener with expiry, click analytics, "
                + "an audit trail, error handling and logging, Cassandra storage with a Redis cache, an OpenAPI contract, delivered by governed AI agents"));
        String md = result.outputs().get(RequirementsAgent.OUTPUT);

        assertEquals("11 stories, 49 acceptance criteria", result.rationale());
        for (StoryCatalog.Story s : StoryCatalog.ALL) assertTrue(md.contains("## " + s.id() + " " + s.title()), s.id());
        assertTrue(md.contains("| AC-1.1 | a valid public http(s) URL | I POST it to /api/v1/links |"));
        assertTrue(md.contains("**As a** visitor, **I want** a short link to take me to the original URL"));
        assertFalse(md.contains("Open questions"));
    }

    @Test
    void requirementsAgentAsksInsteadOfGuessingWhenTheRequirementIsVagueOrUnknown() {
        String md = stories("Make the links faster and more secure");
        assertTrue(md.contains("\"faster\" is not measurable"));
        assertTrue(md.contains("\"secure\" is not measurable"));
        assertTrue(md.contains("No known capability matches this requirement"));
        assertFalse(md.contains("## US-"));
        assertEquals(List.of("US-3"), StoryCatalog.select("links need a TTL").stream().map(StoryCatalog.Story::id).toList());
    }

    // ------------------------------------------------------------------ design

    @Test
    @Verifies({"AC-8.2"})
    void designAgentDiagramsTheRealCodeAndConfirmsTheLayering() {
        var result = new DesignAgent(REPO).execute(input("req", artifact(RequirementsAgent.OUTPUT, stories("url shortener with audit"))));
        String md = result.outputs().get(DesignAgent.OUTPUT);

        assertTrue(result.rationale().endsWith("0 architecture rule(s) violated"), result.rationale());
        assertTrue(md.contains("```mermaid\nflowchart LR"));
        assertTrue(md.contains("shortener_web --> shortener_app"));
        assertTrue(md.contains("sequenceDiagram"));
        assertFalse(md.contains("FAIL"), md);
        assertTrue(md.contains("| US-5 Audit trail | `shortener.app`, `shortener.infra`, `shortener.web` |"));
    }

    @Test
    void designAgentReportsEveryLayeringViolationAndMissingComponent(@TempDir Path root) throws IOException {
        String spring = "import org." + "springframework.stereotype.Component;\n";
        write(root, "src/main/java/com/ascs/shortener/domain/D.java",
                "package com.ascs.shortener.domain;\n" + spring + "class D { com.ascs.shortener.infra.X x; }");
        write(root, "src/main/java/com/ascs/shortener/app/A.java",
                "package com.ascs.shortener.app;\nclass A { com.ascs.shortener.web.W w; com.ascs.shortener.domain.D d; com.ascs.orchestrator.core.O o; }");
        write(root, "src/main/java/com/ascs/shortener/infra/X.java",
                "package com.ascs.shortener.infra;\nclass X { com.ascs.orchestrator.core.O o; }");
        write(root, "src/main/java/com/ascs/orchestrator/core/O.java",
                "package com.ascs.orchestrator.core;\nclass O { com.ascs.shortener.domain.D d; }");
        write(root, "src/main/java/Loose.java", "class Loose {}");

        String md = new DesignAgent(root).execute(input("req", artifact(RequirementsAgent.OUTPUT, "## US-8 Agentic delivery roles\n")))
                .outputs().get(DesignAgent.OUTPUT);
        assertTrue(md.contains("FAIL: shortener.domain"), md);
        assertTrue(md.contains("FAIL: shortener.domain -> shortener.infra"));
        assertTrue(md.contains("FAIL: shortener.app -> shortener.web"));
        assertTrue(md.contains("FAIL: orchestrator.core -> shortener.domain"));
        assertTrue(md.contains("FAIL: shortener.app -> orchestrator.core, shortener.infra -> orchestrator.core"));
        assertTrue(md.contains("`orchestrator.agents` (missing)"));

        String none = new DesignAgent(root).execute(input("req")).outputs().get(DesignAgent.OUTPUT);
        assertTrue(none.contains("No user stories were supplied upstream."));
    }

    // ------------------------------------------------------------------ development

    @Test
    @Verifies({"AC-8.3"})
    void developmentAgentAuditsHistoryAndProposesOneScopedCommitPerComponent() throws Exception {
        CommandRunner git = cmd -> cmd.get(1).equals("log")
                ? List.of("a1b2c3d\tfeat(shortener-app): add audit trail to the service",
                          "b2c3d4e\tupdated stuff",
                          "c3d4e5f\tfeat(core): " + "x".repeat(70),
                          "d4e5f6a")
                : List.of("?? src/main/java/com/ascs/shortener/app/AuditTrail.java",
                          " M src/main/java/com/ascs/shortener/app/ShortenerService.java",
                          " M src/main/java/com/ascs/orchestrator/core/WorkflowEngine.java",
                          "A  src/test/java/com/ascs/shortener/AuditTest.java",
                          " M docs/DESIGN.md",
                          " M README.md",
                          " M pom.xml",
                          "?? src/main/java/com/ascs/Root.java",
                          "?? src/main/java/com/ascs/orchestrator/Top.java",
                          "?? src/main/java/com/ascs/orchestrator/agents/AVeryLongAgentClassNameNumberOne.java",
                          "?? src/main/java/com/ascs/orchestrator/agents/AVeryLongAgentClassNameNumberTwo.java",
                          "?? src/main/java/com/ascs/orchestrator/agents/Three.java",
                          "?? src/main/java/com/ascs/orchestrator/agents/Four.java",
                          "",
                          "?? \"docs/with space.md\"");
        var result = new DevelopmentAgent(git).execute(input("req"));
        String md = result.outputs().get(DevelopmentAgent.OUTPUT);

        assertEquals("1/4 commits conventional; 8 commit(s) proposed", result.rationale());
        assertTrue(md.contains("| `b2c3d4e` | updated stuff | **no** | yes |"));
        assertTrue(md.contains("| yes | **no** |"), "an over-long subject is flagged");
        assertTrue(md.contains("feat(shortener-app): add AuditTrail; update ShortenerService"));
        assertTrue(md.contains("refactor(orchestrator-core): update WorkflowEngine"));
        assertTrue(md.contains("test(shortener): add AuditTest"));
        assertTrue(md.contains("docs: add with space.md; update DESIGN.md, README.md"));
        assertTrue(md.contains("build: update pom.xml"));
        assertTrue(md.contains("feat(app): add Root"));
        assertTrue(md.contains("feat(orchestrator): add Top"));
        assertTrue(md.contains("feat(orchestrator-agents): add AVeryLongAgentClassNameNumberOne, AVer..."),
                "subjects are capped at 72 characters");
    }

    @Test
    void developmentAgentReportsACleanTreeAndAMissingGit() throws Exception {
        String clean = new DevelopmentAgent(cmd -> List.of()).execute(input("req")).outputs().get(DevelopmentAgent.OUTPUT);
        assertTrue(clean.contains("0 of 0 commits"));
        assertTrue(clean.contains("Working tree is clean"));

        var missing = new DevelopmentAgent(cmd -> { throw new IOException("git: not found"); }).execute(input("req"));
        assertEquals("git unavailable", missing.rationale());
        assertTrue(missing.outputs().get(DevelopmentAgent.OUTPUT).contains("git: not found"));
        assertEquals("build", com.ascs.orchestrator.agents.AgentTestBridge.group("other/file.txt"));
        assertEquals("feat(shortener-web)", com.ascs.orchestrator.agents.AgentTestBridge.group(
                "shortener-service/src/main/java/com/ascs/shortener/web/ContractController.java"), "the module prefix is not a scope");
        assertEquals("test(orchestrator-agents)", com.ascs.orchestrator.agents.AgentTestBridge.group(
                "orchestrator/src/test/java/com/ascs/orchestrator/agents/AgentTestBridge.java"));
    }

    @Test
    void commandRunnerRunsRealProcessesAndFailsLoudly() throws Exception {
        CommandRunner real = CommandRunner.in(REPO);
        assertTrue(real.run(List.of("git", "--version")).get(0).startsWith("git version"));

        IOException exit = assertThrows(IOException.class, () -> real.run(List.of("git", "definitely-not-a-subcommand")));
        assertTrue(exit.getMessage().contains("exited"));
        assertThrows(IOException.class, () -> real.run(List.of("no-such-binary-" + System.nanoTime())));

        // waits on stdin forever: must be killed by the timeout instead of hanging the agent
        CommandRunner quick = CommandRunner.in(REPO, Duration.ofMillis(300));
        IOException timeout = assertThrows(IOException.class, () -> quick.run(List.of("git", "hash-object", "--stdin")));
        assertTrue(timeout.getMessage().contains("timed out"));
    }

    // ------------------------------------------------------------------ code review

    @Test
    @Verifies({"AC-8.4"})
    void codeReviewAgentReviewsEveryFileOfTheRealTreeAndApprovesIt() throws Exception {
        long files = Files.walk(REPO).map(p -> REPO.relativize(p).toString().replace('\\', '/'))
                .filter(p -> p.matches("[\\w.-]+/src/(main|test)/java/.*\\.java")).count();
        var result = new CodeReviewAgent(REPO).execute(input("req"));
        String md = result.outputs().get(CodeReviewAgent.OUTPUT);

        assertTrue(md.contains("**Files reviewed:** " + files + " / " + files), md.substring(0, 400));
        assertTrue(md.contains("**Verdict:** **APPROVED**"));
        assertTrue(md.contains("| CR-01 | HIGH | `orchestrator/src/main/java/com/ascs/orchestrator/scenario/ScenarioAgents.java:"));
        assertTrue(md.contains("| ACCEPTED | AWS documented example key id"));
        assertTrue(md.contains("| CR-04 | MEDIUM | `orchestrator/src/main/java/com/ascs/orchestrator/approval/ConsoleApprovalGateway.java:"));
        assertFalse(md.contains("AKIA" + "IOSFODNN7EXAMPLE"), "the report locates secrets without repeating them");
        assertFalse(md.contains("| OPEN |"), md);
    }

    @Test
    void codeReviewAgentFlagsEveryRuleAndHonoursOnlyMatchingWaivers(@TempDir Path root) throws Exception {
        String t = "@" + "Test";
        write(root, "src/main/java/x/Bad.java", String.join("\n",
                "package x;",
                "public class Bad {",
                "    String key = \"AKIA" + "ABCDEFGHIJKLMNOP\";",
                "    void a() { try { run(); } catch (Exception e) {" + "} }",
                "    void b(Exception e) { e.print" + "StackTrace(); System.out.println(1); }",
                "    // TO" + "DO: tidy",
                "    java.util.Random r = new Random" + "(42);",
                "    // review:accepted CR-0" + "2 not this rule",
                "    // FIX" + "ME later",
                "    // review:accepted CR-0" + "5 tracked as issue 12",
                "    // TO" + "DO accepted",
                "}"));
        write(root, "src/main/java/x/Cli.java", "package x;\n/** Docs. */\n@Deprecated\npublic final class Cli {\n"
                + "  public static void main(String[] a) { System.out.println(a); }\n}");
        write(root, "src/main/java/Top.java", "public class Top {}");
        write(root, "src/test/java/x/SomeTest.java", "package x;\nclass SomeTest {\n " + t + " void t() { System.out.println(1); }\n}");

        var result = new CodeReviewAgent(root).execute(input("req"));
        String md = result.outputs().get(CodeReviewAgent.OUTPUT);

        assertTrue(result.rationale().endsWith("CHANGES REQUESTED"), result.rationale());
        assertTrue(md.contains("**Files reviewed:** 4 / 4 (3 main, 1 test)"));
        for (String rule : List.of("CR-01", "CR-02", "CR-03", "CR-04", "CR-06")) {
            assertTrue(md.contains("| " + rule + " |") && md.contains("`src/main/java/x/Bad.java:"), rule);
        }
        assertTrue(md.contains("| CR-07 | LOW | `src/main/java/x/Bad.java:2` | OPEN |"));
        assertTrue(md.contains("| CR-07 | LOW | `src/main/java/Top.java:1` | OPEN |"), "a declaration on line 1 has no Javadoc");
        assertTrue(md.contains("| CR-05 | LOW | `src/main/java/x/Bad.java:9` | OPEN |"), "a waiver for another rule does not count");
        assertTrue(md.contains("| CR-05 | LOW | `src/main/java/x/Bad.java:11` | ACCEPTED | tracked as issue 12 |"));
        assertFalse(md.contains("Cli.java:"), "CLI entry points may print; documented types pass CR-07");
        assertFalse(md.contains("SomeTest.java:"), "console output in tests is not a finding");
        assertFalse(md.contains("ABCDEFGHIJKLMNOP"));

        write(root, "src/main/java/x/Bad.java", "package x;\n/** Fixed. */\npublic class Bad {}\n");
        Files.delete(root.resolve("src/main/java/Top.java"));
        String clean = new CodeReviewAgent(root).execute(input("req")).outputs().get(CodeReviewAgent.OUTPUT);
        assertTrue(clean.contains("## Findings\n\nNone."));
        assertTrue(clean.contains("**APPROVED**"));
    }

    // ------------------------------------------------------------------ QA

    private static final String JACOCO_PARTIAL = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="x"><sessioninfo id="s" start="1" dump="2"/>
            <package name="com/ascs/a"><class name="com/ascs/a/A" sourcefilename="A.java"/>
            <sourcefile name="A.java"><line nr="3" mi="0" ci="2" mb="1" cb="1"/><line nr="4" mi="2" ci="0" mb="0" cb="0"/>
            <line nr="5" mi="2" ci="0" mb="0" cb="0"/><line nr="9" mi="1" ci="0" mb="0" cb="0"/><line nr="10" mi="0" ci="1" mb="0" cb="0"/>
            <counter type="LINE" missed="3" covered="2"/><counter type="BRANCH" missed="1" covered="1"/></sourcefile>
            <sourcefile name="B.java"><line nr="1" mi="0" ci="3" mb="0" cb="0"/><counter type="LINE" missed="0" covered="1"/></sourcefile>
            <sourcefile name="C.java"><line nr="7" mi="0" ci="3" mb="1" cb="1"/><counter type="LINE" missed="0" covered="1"/><counter type="BRANCH" missed="1" covered="1"/></sourcefile>
            <counter type="LINE" missed="3" covered="3"/></package>
            <package name="com/ascs/b"/>
            <counter type="LINE" missed="3" covered="3"/><counter type="BRANCH" missed="1" covered="1"/><counter type="METHOD" missed="0" covered="2"/>
            </report>
            """;

    private static final String JACOCO_FULL = """
            <?xml version="1.0" encoding="UTF-8"?><report name="x">
            <package name="com/ascs/a"><sourcefile name="A.java"><line nr="1" mi="0" ci="1" mb="0" cb="2"/>
            <counter type="LINE" missed="0" covered="1"/><counter type="BRANCH" missed="0" covered="2"/></sourcefile></package>
            <counter type="LINE" missed="0" covered="1"/><counter type="BRANCH" missed="0" covered="2"/><counter type="METHOD" missed="0" covered="1"/>
            </report>
            """;

    private static void testTree(Path root, String extra, String... criteria) throws IOException {
        String v = "@" + "Verifies(";
        String t = "@" + "Test";
        StringBuilder src = new StringBuilder("package x;\nclass ATest {\n");
        src.append("  ").append(t).append("\n  ").append(v).append("{\"").append(String.join("\", \"", criteria)).append("\"})\n  void works() {}\n");
        src.append("  @" + "ParameterizedTest\n  void param(int i) {}\n");
        src.append(extra).append("}\n");
        write(root, "src/test/java/x/ATest.java", src.toString());
        write(root, "src/test/java/x/Helper.java", "package x;\nclass Helper {}\n");
    }

    @Test
    @Verifies({"AC-8.5"})
    void qaAgentReportsCoverageFunctionalTraceabilityAndEveryGap(@TempDir Path root) throws Exception {
        testTree(root, "  " + "@" + "Verifies(\"AC-1.2\")\n", "AC-1.1", "AC-9.9");
        Path xml = root.resolve("jacoco.xml");
        Files.writeString(xml, JACOCO_PARTIAL);
        Artifact stories = artifact(RequirementsAgent.OUTPUT, "| AC-1.1 | g | w | t |\n| AC-1.2 | g | w | t |\n| AC-1.3 | g | w | t |\n");

        var result = new QaAgent(root, xml, 100).execute(input("req", stories));
        String md = result.outputs().get(QaAgent.OUTPUT);

        assertTrue(result.rationale().startsWith("target not met for: Line coverage, Branch coverage, Functional coverage"), result.rationale());
        assertTrue(md.contains("| Unit tests | 2 tests in 1 classes |"));
        assertTrue(md.contains("| Line coverage | 50.0% (3/6) | 100% | **no** |"));
        assertTrue(md.contains("| Method coverage | 100.0% (2/2) | 100% | yes |"));
        assertTrue(md.contains("| Functional coverage (acceptance criteria) | 66.7% (2/3) | 100% | **no** |"));
        assertTrue(md.contains("| `com.ascs.b` | 100.0% (0/0) |"), "packages without counters count as fully covered");
        assertTrue(md.contains("| `com/ascs/a/A.java` | 40.0% (2/5) | 50.0% (1/2) | 4-5, 9 | 3 |"), md);
        assertFalse(md.contains("B.java`"), "fully covered files are not gaps");
        assertTrue(md.contains("| `com/ascs/a/C.java` | 100.0% (1/1) | 50.0% (1/2) | - | 7 |"), "full lines but partial branches is a gap");
        assertTrue(md.contains("| AC-1.1 | ATest#works |"));
        assertTrue(md.contains("| AC-1.2 | ATest#? |"), "a claim with no following method is still traced");
        assertTrue(md.contains("- Acceptance criterion AC-1.3 has no verifying test."));
        assertTrue(md.contains("- Traceability error: AC-9.9 in ATest#works references an unknown criterion."));
    }

    @Test
    void qaAgentSaysWhenNothingWasMeasuredAndWhenEverythingIsMet(@TempDir Path root) throws Exception {
        testTree(root, "", "AC-1.1");
        String unmeasured = new QaAgent(root, root.resolve("missing.xml"), 99.5).execute(input("req")).outputs().get(QaAgent.OUTPUT);
        assertTrue(unmeasured.contains("Target: **99.5%**"));
        assertTrue(unmeasured.contains("| Line coverage | not measured | 99.5% | no |"));
        assertTrue(unmeasured.contains("No JaCoCo report at"));
        assertTrue(unmeasured.contains("No acceptance criteria were supplied upstream."));
        assertTrue(unmeasured.contains("- Unit coverage was not measured."));

        Path xml = root.resolve("full.xml");
        Files.writeString(xml, JACOCO_FULL);
        var met = new QaAgent(root, xml, 100).execute(input("req", artifact(RequirementsAgent.OUTPUT, "| AC-1.1 | g | w | t |\n")));
        assertEquals("all coverage targets met", met.rationale());
        assertTrue(met.outputs().get(QaAgent.OUTPUT).contains("None: every target was met."));

        assertEquals(100.0, new QaAgent.Counter(0, 0).percent());
        assertEquals("-", com.ascs.orchestrator.agents.AgentTestBridge.ranges(List.of()));
        assertEquals("1-3, 5, 7-8", com.ascs.orchestrator.agents.AgentTestBridge.ranges(List.of(1, 2, 3, 5, 7, 8)));
    }

    @Test
    void qaAgentMergesTheReportsAndTestsOfEveryModule(@TempDir Path root) throws Exception {
        String v = "@" + "Verifies(";
        String t = "@" + "Test";
        write(root, "service/src/test/java/x/ATest.java", "package x;\nclass ATest {\n  " + t + " " + v + "\"AC-1.1\") void a() {}\n}\n");
        write(root, "engine/src/test/java/y/BTest.java", "package y;\nclass BTest {\n  " + t + " " + v + "\"AC-1.2\") void b() {}\n}\n");
        write(root, "service/target/site/jacoco/jacoco.xml", JACOCO_PARTIAL);
        write(root, "engine/target/site/jacoco/jacoco.xml", JACOCO_FULL);
        write(root, "service/target/surefire-reports/TEST-x.ATest.xml", "<testsuite><testcase name=\"a\" classname=\"x.ATest\"/></testsuite>");
        write(root, "engine/target/surefire-reports/TEST-y.BTest.xml", "<testsuite><testcase name=\"b\" classname=\"y.BTest\"/></testsuite>");
        Files.createDirectories(root.resolve("docs")); // not a module: no src folder

        assertEquals(List.of("engine", "service"), SourceFile.modules(root));
        assertEquals(List.of(root.resolve("engine/target/site/jacoco/jacoco.xml"), root.resolve("service/target/site/jacoco/jacoco.xml")),
                DeliveryPipeline.jacocoReports(root));
        Artifact stories = artifact(RequirementsAgent.OUTPUT, "| AC-1.1 | g | w | t |\n| AC-1.2 | g | w | t |\n");
        String md = DeliveryPipeline.agents(root, DeliveryPipeline.jacocoReports(root), cmd -> List.of(), 100).get("qa")
                .execute(input("req", stories)).outputs().get(QaAgent.OUTPUT);

        assertTrue(md.contains("| Unit tests | 2 tests in 2 classes |"), md);
        assertTrue(md.contains("| Line coverage | 57.1% (4/7) |"), "line counters of both modules are summed");
        assertTrue(md.contains("| Branch coverage | 75.0% (3/4) |"));
        assertTrue(md.contains("| Method coverage | 100.0% (3/3) |"));
        assertTrue(md.contains("| Functional coverage (acceptance criteria) | 100.0% (2/2) |"), "both modules' passing tests count");
        assertTrue(md.contains("Only tests that ran and passed count"));
        assertThrows(java.io.UncheckedIOException.class, () -> SourceFile.modules(root.resolve("no-such-project")));
    }

    @Test
    void qaAgentCountsOnlyTestsThatRanAndPassed(@TempDir Path root) throws Exception {
        String v = "@" + "Verifies(";
        String t = "@" + "Test";
        write(root, "src/test/java/x/ATest.java", "package x;\nclass ATest {\n"
                + "  " + t + " " + v + "\"AC-1.1\") void works() {}\n"
                + "  " + t + " " + v + "\"AC-1.2\") void needsDocker() {}\n"
                + "  " + t + " " + v + "\"AC-1.3\") void flaky(int run) {}\n"
                + "  " + t + " " + v + "\"AC-1.4\") void neverRan() {}\n}\n");
        Path results = Files.createDirectories(root.resolve("target/surefire-reports"));
        Files.writeString(results.resolve("TEST-x.ATest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="x.ATest">
                  <testcase name="works" classname="x.ATest"/>
                  <testcase name="needsDocker" classname="x.ATest"><skipped message="Docker not available"/></testcase>
                  <testcase name="flaky(int)[1]" classname="x.ATest"/>
                  <testcase name="flaky(int)[2]" classname="x.ATest"><failure message="boom"/></testcase>
                  <testcase name="flaky(int)[3]" classname="x.ATest"/>
                  <testcase name="crashed" classname="x.ATest"><error message="NPE"/></testcase>
                </testsuite>
                """);
        Files.writeString(results.resolve("x.ATest.txt"), "not a report");
        Artifact stories = artifact(RequirementsAgent.OUTPUT, "| AC-1.1 | g | w | t |\n| AC-1.2 | g | w | t |\n"
                + "| AC-1.3 | g | w | t |\n| AC-1.4 | g | w | t |\n");

        String md = new QaAgent(root, root.resolve("none.xml"), results, 100).execute(input("req", stories)).outputs().get(QaAgent.OUTPUT);
        assertTrue(md.contains("| Functional coverage (acceptance criteria) | 25.0% (1/4) |"), md);
        assertTrue(md.contains("Only tests that ran and passed count"));
        assertTrue(md.contains("| AC-1.1 | ATest#works |"));
        assertTrue(md.contains("| AC-1.2 | ATest#needsDocker (skipped) |"));
        assertTrue(md.contains("| AC-1.3 | ATest#flaky (failed) |"), "the worst invocation of a parameterized test wins");
        assertTrue(md.contains("| AC-1.4 | ATest#neverRan (not run) |"));
        assertTrue(md.contains("- Acceptance criterion AC-1.2 has no passing test (declared by ATest#needsDocker)."));

        Path empty = Files.createDirectories(root.resolve("empty-reports"));
        String noRuns = new QaAgent(root, root.resolve("none.xml"), empty, 100).execute(input("req", stories)).outputs().get(QaAgent.OUTPUT);
        assertTrue(noRuns.contains("Test results not found"), "a directory without reports means nothing was executed");
        assertTrue(new QaAgent(root, root.resolve("none.xml"), root.resolve("no-such-dir"), 100).execute(input("req", stories))
                .outputs().get(QaAgent.OUTPUT).contains("Test results not found"));
    }

    // ------------------------------------------------------------------ release readiness

    @Test
    void releaseAgentRecommendsFromTheEvidenceAndCitesItsVersions() {
        String approved = "- **Verdict:** **APPROVED**";
        String qaMet = "## 1. Summary\n\n| Line coverage | 100% | 100% | yes |\n\n## 2. Unit";
        String qaGap = "## 1. Summary\n\n| Line coverage | 90% | 100% | **no** |\n\n## 2. Unit";

        assertTrue(release(artifact(CodeReviewAgent.OUTPUT, approved), artifact(QaAgent.OUTPUT, qaMet))
                .contains("**Recommendation:** READY\n"));
        String gaps = release(artifact(CodeReviewAgent.OUTPUT, approved), artifact(QaAgent.OUTPUT, qaGap));
        assertTrue(gaps.contains("READY WITH DOCUMENTED GAPS"));
        assertTrue(gaps.contains("(evidence: code-review.md@1)") && gaps.contains("(evidence: qa-report.md@1)"));
        assertTrue(gaps.contains("| Line coverage | 90% | 100% | **no** |"), "the QA summary is quoted for the approver");

        String notReady = release(artifact(CodeReviewAgent.OUTPUT, "**CHANGES REQUESTED**"));
        assertTrue(notReady.contains("**Recommendation:** NOT READY"));
        assertTrue(notReady.contains("(evidence: missing)"));
        assertFalse(release(artifact(CodeReviewAgent.OUTPUT, approved), artifact(QaAgent.OUTPUT, "no summary | no |"))
                .contains("## 1. Summary"));
        assertFalse(release(artifact(CodeReviewAgent.OUTPUT, approved), artifact(QaAgent.OUTPUT, "## 1. Summary | no | but no end"))
                .contains("but no end"), "a summary without its closing section is not quoted");
    }

    private static String release(Artifact... upstream) {
        return new ReleaseReadinessAgent().execute(input("req", upstream)).outputs().get(ReleaseReadinessAgent.OUTPUT);
    }

    @Test
    void sourceScanSkipsMissingRootsAndReportsUnreadableFiles(@TempDir Path root) throws IOException {
        assertTrue(com.ascs.orchestrator.agents.SourceFile.scan(root, "src/main/java").isEmpty());
        Files.createDirectories(root.resolve("src/main/java/Broken.java")); // a directory with a .java name
        assertThrows(java.io.UncheckedIOException.class, () -> com.ascs.orchestrator.agents.SourceFile.scan(root, "src/main/java"));
    }

    @Test
    void storyCatalogIdsAreUniqueAndSequential() {
        List<String> ids = StoryCatalog.ALL.stream().flatMap(s -> s.criteria().stream()).map(StoryCatalog.Criterion::id).toList();
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertEquals(49, ids.size());
        assertTrue(StoryCatalog.ALL.stream().allMatch(s -> !s.components().isEmpty()));
    }
}
