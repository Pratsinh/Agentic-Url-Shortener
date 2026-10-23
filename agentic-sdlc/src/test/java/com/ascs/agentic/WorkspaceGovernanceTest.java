package com.ascs.agentic;

import com.ascs.agentic.tools.PathGuard;
import com.ascs.agentic.tools.WorkspaceTools;
import com.ascs.agentic.tools.WorkspaceWritePolicy;
import com.ascs.agentic.tools.WriteScope;
import com.ascs.agentic.workspace.Commands;
import com.ascs.agentic.workspace.GitWorkspace;
import com.ascs.agentic.workspace.TestReport;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.tools.ToolCall;
import com.ascs.orchestrator.tools.ToolGateway;
import com.ascs.orchestrator.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Isolation, least privilege and change control around the workspace tools, on a real git worktree. */
class WorkspaceGovernanceTest {

    @TempDir
    Path tmp;
    Path repo;
    GitWorkspace ws;
    final AuditLog audit = new AuditLog(Clock.systemUTC());
    ToolGateway gw;
    final List<String> builds = new java.util.ArrayList<>();
    static final ToolGateway.Context IMPL = new ToolGateway.Context("run1", "impl-T1", 1);

    @BeforeEach
    void setUp() throws Exception {
        repo = TestRepo.create(tmp.resolve("repo"));
        ws = GitWorkspace.create(repo, repo.resolve(".agentic/worktrees"), "run1");
        WorkspaceTools tools = new WorkspaceTools(ws, (dir, module, filter) -> {
            builds.add(module + ":" + filter);
            return new TestReport("mvn test", 0, false, 5, 3, 0, 0, 0, List.of(), 0.9, "ok");
        }, Set.of("shortener-service"));
        WriteScope scope = new WriteScope(Set.of("shortener-service/src/main/java/demo/Greeter.java"),
                Set.of("shortener-service/src/main/java/demo"), List.of("shortener-service/src/test/"));
        ToolGateway.Builder b = ToolGateway.builder(audit, ScriptedApprovalGateway.silent())
                .policy(new WorkspaceWritePolicy(Map.of("impl-T1", scope)::get, p -> {
                    try { return ws.existedAtBase(p); } catch (Exception e) { return true; }
                }));
        tools.all().forEach(b::tool);
        b.grant("impl-T1", WorkspaceTools.LIST, WorkspaceTools.READ, WorkspaceTools.SEARCH, WorkspaceTools.WRITE,
                WorkspaceTools.REPLACE, WorkspaceTools.TEST, WorkspaceTools.DIFF);
        b.grant("review", WorkspaceTools.READ, WorkspaceTools.DIFF);
        gw = b.build();
    }

    private ToolResult call(ToolGateway.Context ctx, String tool, Map<String, String> args) throws InterruptedException {
        return gw.invoke(ctx, new ToolCall(tool, args));
    }

    @Test
    void writesStayInTheWorktreeAndTheMainCheckoutIsUntouched() throws Exception {
        ToolResult r = call(IMPL, WorkspaceTools.REPLACE, Map.of("path", "shortener-service/src/main/java/demo/Greeter.java",
                "old_text", "\"hi\"", "new_text", "\"hello\""));
        assertEquals(200, r.httpStatus(), r.content());
        assertTrue(Files.readString(ws.root().resolve("shortener-service/src/main/java/demo/Greeter.java")).contains("hello"));
        assertTrue(Files.readString(repo.resolve("shortener-service/src/main/java/demo/Greeter.java")).contains("\"hi\""), "main tree untouched");
        assertEquals(List.of("shortener-service/src/main/java/demo/Greeter.java"), ws.changedFiles());
        assertTrue(call(IMPL, WorkspaceTools.DIFF, Map.of()).content().contains("+    String greet() { return \"hello\"; }"));
    }

    @Test
    void pathTraversalAbsolutePathsAndGitInternalsAreRefusedCritically() throws Exception {
        for (String bad : List.of("../escape.txt", "/etc/passwd", "C:/Windows/x", ".git/config")) {
            ToolResult r = call(IMPL, WorkspaceTools.WRITE, Map.of("path", bad, "content", "x"));
            assertEquals(ToolResult.Status.DENIED, r.status(), bad);
        }
        assertTrue(audit.events().stream().anyMatch(e -> e.type() == AuditLog.Type.POLICY_VIOLATION && e.detail().contains("CRITICAL")));
        assertFalse(Files.exists(tmp.resolve("repo/.agentic/worktrees/escape.txt")));
        assertTrue(call(IMPL, WorkspaceTools.READ, Map.of("path", "README.md")).reason().contains("revoked"),
                "after a CRITICAL violation the run loses all tool access");
    }

    @Test
    void protectedCiPathIsCriticalAndRevokesTheRun() throws Exception {
        ToolResult r = call(IMPL, WorkspaceTools.WRITE, Map.of("path", ".github/workflows/ci.yml", "content", "evil"));
        assertTrue(r.reason().startsWith("CRITICAL policy violation"), r.reason());
        assertEquals("on: push\n", Files.readString(ws.root().resolve(".github/workflows/ci.yml")).replace("\r", ""));
    }

    @Test
    void buildDescriptorsAndOutOfScopeFilesAreBlockedButTheAgentMayContinue() throws Exception {
        assertTrue(call(IMPL, WorkspaceTools.WRITE, Map.of("path", "pom.xml", "content", "<x/>")).reason().contains("change-controlled"));
        assertTrue(call(IMPL, WorkspaceTools.WRITE, Map.of("path", "README.md", "content", "x")).reason().contains("outside this stage's approved write scope"));
        assertEquals(200, call(IMPL, WorkspaceTools.WRITE, Map.of("path", "shortener-service/src/main/java/demo/NewThing.java",
                "content", "package demo;\nclass NewThing {}\n")).httpStatus(), "a new file next to a listed one is allowed");
        assertEquals(200, call(IMPL, WorkspaceTools.WRITE, Map.of("path", "shortener-service/src/test/java/demo/NewTest.java",
                "content", "package demo;\n")).httpStatus(), "the module's test tree is allowed");
    }

    @Test
    void theReviewerCannotWriteAndUnknownStagesHaveNoTools() throws Exception {
        ToolGateway.Context review = new ToolGateway.Context("run1", "review", 1);
        assertEquals("tool not granted to stage review", call(review, WorkspaceTools.WRITE, Map.of("path", "x", "content", "y")).reason());
        assertEquals(200, call(review, WorkspaceTools.READ, Map.of("path", "README.md")).httpStatus());
    }

    @Test
    void readSearchListAndExactReplaceSemantics() throws Exception {
        assertTrue(call(IMPL, WorkspaceTools.LIST, Map.of("path", "shortener-service")).content().contains("demo/Greeter.java"));
        assertTrue(call(IMPL, WorkspaceTools.SEARCH, Map.of("pattern", "class \\w+")).content().contains("Greeter.java:3:"));
        assertEquals(400, call(IMPL, WorkspaceTools.SEARCH, Map.of("pattern", "(")).httpStatus());
        assertTrue(call(IMPL, WorkspaceTools.READ, Map.of("path", "README.md", "start_line", "1", "end_line", "1")).content().startsWith("1: # test repo"));
        assertEquals(404, call(IMPL, WorkspaceTools.READ, Map.of("path", "nope.txt")).httpStatus());
        ToolResult ambiguous = call(IMPL, WorkspaceTools.REPLACE, Map.of("path", "shortener-service/src/main/java/demo/Greeter.java",
                "old_text", "e", "new_text", "E"));
        assertEquals(409, ambiguous.httpStatus());
    }

    @Test
    void testsRunOnlyThroughTheAllowlistedRunner() throws Exception {
        assertTrue(call(IMPL, WorkspaceTools.TEST, Map.of("module", "shortener-service", "tests", "GreeterTest")).content().startsWith("PASSED"));
        assertEquals(400, call(IMPL, WorkspaceTools.TEST, Map.of("module", "orchestrator")).httpStatus());
        assertEquals(List.of("shortener-service:GreeterTest"), builds);
    }

    @Test
    void commitResetAndDiscardManageTheRunBranch() throws Exception {
        TestRepo.write(ws.root(), "shortener-service/src/main/java/demo/Extra.java", "class Extra {}\n");
        String commit = ws.commit("feat: extra", "agentic-sdlc", "a@b");
        assertTrue(TestRepo.git(repo, "log", "--format=%s", "agentic/run1", "-1").contains("feat: extra"));
        assertNotEquals(ws.baseCommit(), commit);
        ws.resetToBase();
        assertEquals(ws.baseCommit(), TestRepo.git(ws.root(), "rev-parse", "HEAD").strip());
        ws.discard();
        assertFalse(TestRepo.git(repo, "branch", "--list", "agentic/run1").contains("agentic/run1"));
    }

    @Test
    void pathGuardNormalizesAndRefuses() {
        assertEquals("a/b.txt", PathGuard.normalize(".\\a\\b.txt"));
        assertThrows(IllegalArgumentException.class, () -> PathGuard.normalize("a/../../b"));
        assertThrows(IllegalArgumentException.class, () -> PathGuard.normalize(" "));
    }

    @Test
    void childProcessesDoNotInheritCredentials() {
        Map<String, String> scrubbed = Commands.scrub(Map.of("ANTHROPIC_API_KEY", "x", "GITHUB_TOKEN", "y", "DB_PASSWORD", "z", "PATH", "p"));
        assertEquals(Map.of("PATH", "p"), scrubbed);
    }
}
