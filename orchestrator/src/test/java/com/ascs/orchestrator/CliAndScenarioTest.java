package com.ascs.orchestrator;

import com.ascs.orchestrator.approval.ConsoleApprovalGateway;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.model.ApprovalRequest;
import com.ascs.orchestrator.model.Decision;
import com.ascs.orchestrator.model.Impact;
import com.ascs.orchestrator.model.RunOutcome;
import com.ascs.orchestrator.planner.CodebaseImpactAnalyzer;
import com.ascs.orchestrator.scenario.ScenarioRunner;
import com.ascs.orchestrator.scenario.Scenarios;
import com.ascs.orchestrator.sdlc.OrchestratorDemo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Command-line entry points, the terminal approver and scenario variants. */
class CliAndScenarioTest {

    private static final ApprovalRequest REQUEST = new ApprovalRequest("r", "release", Impact.HIGH, "ship it?",
            Map.of("checklist.md", "all green"), List.of("security.warn: unsigned artifact"));

    private static Decision ask(String typed) throws Exception {
        return ask(new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
    }

    private static Decision ask(InputStream in, ByteArrayOutputStream screen) throws Exception {
        CompletableFuture<Decision> d = new CompletableFuture<>();
        new ConsoleApprovalGateway(in, new PrintStream(screen, true, StandardCharsets.UTF_8), "alice").request(REQUEST, d::complete);
        return d.get(10, TimeUnit.SECONDS);
    }

    @Test
    void terminalApproverShowsTheProposalAndRecordsTheAnswer() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        Decision yes = ask(new ByteArrayInputStream("yes ship it\n".getBytes(StandardCharsets.UTF_8)), screen);
        assertEquals(Decision.approve("alice", "ship it"), yes);
        String shown = screen.toString(StandardCharsets.UTF_8);
        assertTrue(shown.contains("APPROVAL REQUIRED: stage 'release' (impact HIGH)"));
        assertTrue(shown.contains("--- checklist.md ---") && shown.contains("WARNING: security.warn: unsigned artifact"));

        assertEquals(Decision.reject("alice", "not yet"), ask("no not yet\n"));
        assertEquals(Decision.reject("alice", "eof"), ask(""));
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("terminal closed"); }
        };
        Decision error = ask(broken, new ByteArrayOutputStream());
        assertFalse(error.approved());
        assertTrue(error.comment().startsWith("console error"));
        assertNotNull(new ConsoleApprovalGateway());
    }

    @Test
    void orchestratorDemoRunsWithDefaultAndCustomRequirements() {
        OrchestratorDemo.main(new String[0]);
        OrchestratorDemo.main(new String[]{"Add", "QR", "codes"});
    }

    @Test
    void scenarioRunnerWritesTheReport(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("nested/SCENARIOS.md");
        ScenarioRunner.main(new String[]{".", "--out", out.toString()});
        String md = Files.readString(out);
        assertTrue(md.startsWith("# Scenario runs"));
        assertTrue(md.contains("## Scenario 3"));
        assertTrue(md.contains("hash chain **verified intact**"));

        Path flat = Path.of("scenario-runner-test.md"); // a bare file name has no parent directory to create
        try {
            ScenarioRunner.main(new String[]{"--out", flat.toString()});
            assertTrue(Files.exists(flat));
        } finally {
            Files.deleteIfExists(flat);
        }
    }

    @Test
    void scenarioRunnerCanBeDrivenByAHumanOnTheTerminal() throws Exception {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream("y\n".repeat(40).getBytes(StandardCharsets.UTF_8)));
        try {
            ScenarioRunner.main(new String[]{"--interactive"});
        } finally {
            System.setIn(original);
        }
    }

    @Test
    void scenariosUseAnInjectedApproverAndSurviveAMissingSourceTree() {
        Scenarios noTree = new Scenarios(Path.of("no-such-project"), ScriptedApprovalGateway.approveAll("lead", "ok"));
        Scenarios.Result r = noTree.brownfield();
        assertEquals(RunOutcome.SUCCEEDED, r.runs().get(1).outcome());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("compensation executed")));
    }

    @Test
    void impactAnalyzerSkipsMissingRootsAndReportsUnreadableSources(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("Broken.java")); // a directory with a .java name cannot be read
        CodebaseImpactAnalyzer analyzer = new CodebaseImpactAnalyzer(tmp.resolve("absent"), src);
        assertTrue(analyzer.available());
        assertThrows(UncheckedIOException.class, () -> analyzer.analyze("Anything"));

        Path ok = tmp.resolve("ok");
        Files.createDirectories(ok);
        Files.writeString(ok.resolve("Core.java"), "class Core {}");
        Files.writeString(ok.resolve("Service.java"), "class Service { Core c; }");
        Files.writeString(ok.resolve("Api.java"), "class Api { Service s; }");
        assertTrue(new CodebaseImpactAnalyzer.Report(List.of("X"), List.of(), List.of()).toMarkdown().contains("- none"));
        String md = new CodebaseImpactAnalyzer(ok).analyze("Core").toMarkdown();
        assertTrue(md.contains("- Service  ["));
        assertTrue(md.contains("## Transitively impacted (depend on a directly impacted class)\n- Api  ["), md);
    }
}
