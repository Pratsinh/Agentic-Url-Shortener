package com.ascs.agentic;

import com.ascs.agentic.config.AgenticConfig;
import com.ascs.agentic.human.HumanChannel;
import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.Llm;
import com.ascs.agentic.llm.Redactor;
import com.ascs.agentic.pipeline.AgenticPipeline;
import com.ascs.agentic.pipeline.RunRecorder;
import com.ascs.agentic.vcs.PullRequests;
import com.ascs.agentic.workspace.BuildRunner;
import com.ascs.agentic.workspace.TestReport;
import com.ascs.orchestrator.model.StageResult;
import com.ascs.orchestrator.model.StageStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole pipeline (both workflows, real git worktree, real gateway and policies, real audit persistence) with a
 * SCRIPTED model and a fake build, so every governance path can be exercised deterministically and offline. These
 * tests prove the orchestration and controls; they are not evidence of model capability (see docs/scenarios for
 * the live runs).
 */
class PipelineEndToEndTest {

    static final String GREETER = "shortener-service/src/main/java/demo/Greeter.java";
    static final String GREETER_TEST = "shortener-service/src/test/java/demo/GreeterTest.java";

    @TempDir
    Path tmp;
    Path repo;
    final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        repo = TestRepo.create(tmp.resolve("repo"));
    }

    AgenticConfig config() {
        return new AgenticConfig(repo, repo.resolve(".agentic/runs"), repo.resolve(".agentic/worktrees"), "scripted-model",
                "scripted-model", "high", 4000, false, 10.0, 10, "mvn", 2, Duration.ofMinutes(2), Duration.ofSeconds(30),
                Duration.ofMinutes(1), "shortener-service", List.of("shortener-service", "shortener-api"), true);
    }

    /** Fake build: passes, and counts one extra test once the change is in. */
    static final BuildRunner BUILD = (dir, module, filter) -> {
        boolean changed = Files.readString(dir.resolve(GREETER)).contains("farewell");
        return new TestReport("mvn test", 0, false, 10, changed ? 11 : 10, 0, 0, 0, List.of(), 0.95, "BUILD SUCCESS");
    };

    static Map<String, Object> analysis(boolean ambiguous) {
        return Map.of("kind", ambiguous ? "AMBIGUOUS" : "GREENFIELD", "title", "Add a farewell greeting",
                "problemStatement", "The Greeter must also be able to say goodbye through a farewell() method.",
                "acceptanceCriteria", List.of(Map.of("id", "AC-1", "text", "Greeter.farewell() returns a goodbye message")),
                "ambiguities", ambiguous ? List.of(Map.of("id", "AMB-1", "question", "Which goodbye wording?",
                        "whyItMatters", "visible to users", "proposedAssumption", "use 'bye'", "blocking", true)) : List.of(),
                "impactedAreas", List.of(Map.of("path", GREETER, "changeType", "MODIFY", "reason", "add the method")),
                "risks", List.of(), "outOfScope", List.of());
    }

    static final Map<String, Object> PLAN = Map.of("summary", "feat: add farewell greeting",
            "design", "Add a farewell() method next to greet() in Greeter, returning a constant message; cover it with a unit test.",
            "tasks", List.of(Map.of("id", "T1", "title", "Add farewell()", "description", "Add farewell() to Greeter and a test for it.",
                    "module", "shortener-service", "files", List.of(GREETER, GREETER_TEST), "dependsOn", List.of(),
                    "acceptanceCriteria", List.of("AC-1"))),
            "testStrategy", "Unit test for the new method; full module suite must stay green.",
            "compatibility", "greet() is unchanged.", "rollback", "Revert the commit.");

    ScriptedProvider model(boolean ambiguous, Function<Integer, Llm.Turn> implementer) {
        AtomicInteger planCalls = new AtomicInteger();
        return new ScriptedProvider()
                .on("requirements-analyst", (s, turn, sent) -> turn == 1
                        ? ScriptedProvider.call("list_files", Map.of("path", "shortener-service"))
                        : ScriptedProvider.call("submit_requirement_analysis", analysis(ambiguous)))
                .on("planner", (s, turn, sent) -> {
                    planCalls.incrementAndGet();
                    return ScriptedProvider.call("submit_plan", PLAN);
                })
                .on("implementer", (s, turn, sent) -> implementer.apply(turn))
                .on("reviewer", (s, turn, sent) -> ScriptedProvider.call("submit_review", Map.of("verdict", "APPROVE",
                        "summary", "The change is small, correct and tested.", "findings", List.of(),
                        "criteria", List.of(Map.of("id", "AC-1", "met", true, "evidence", "GreeterTest")))))
                .on("docs-writer", (s, turn, sent) -> turn == 1
                        ? ScriptedProvider.call("write_file", Map.of("path", "docs/changes/" + s.runId() + ".md", "content",
                        "# Farewell\n\n## User stories\n- As a user, I want a farewell greeting, so that a conversation ends politely (AC-1)\n"))
                        : ScriptedProvider.call("submit_docs", Map.of("summary", "documented the farewell method",
                        "filesWritten", List.of("docs/changes/" + s.runId() + ".md"))));
    }

    static Llm.Turn goodImplementer(int turn) {
        return switch (turn) {
            case 1 -> ScriptedProvider.call("replace_in_file", Map.of("path", GREETER, "old_text", "String greet() { return \"hi\"; }",
                    "new_text", "String greet() { return \"hi\"; }\n    String farewell() { return \"bye\"; }"));
            case 2 -> ScriptedProvider.call("run_tests", Map.of("module", "shortener-service", "tests", "GreeterTest"));
            default -> ScriptedProvider.call("submit_implementation", Map.of("summary", "added farewell()",
                    "filesChanged", List.of(GREETER), "testsPassedLocally", true, "notes", ""));
        };
    }

    /** Scripted human: decides per question kind. */
    HumanChannel human(String responder, Function<String, HumanChannel.Verdict> onApproval, String feedback) {
        return q -> {
            asked.add(q.id());
            if (q.id().startsWith("clarify")) return Optional.of(new HumanChannel.Reply(responder, HumanChannel.Verdict.ANSWER, "AMB-1: accept", "test"));
            HumanChannel.Verdict v = onApproval.apply(q.id());
            return Optional.of(new HumanChannel.Reply(responder, v, v == HumanChannel.Verdict.REVISE ? feedback : "ok", "test"));
        };
    }

    AgenticPipeline pipeline(ScriptedProvider p, HumanChannel h) {
        return new AgenticPipeline(config(), p, BUILD, inbox -> h, Clock.systemUTC(), new Redactor(Map.of()));
    }

    static StageStatus status(AgenticPipeline.Result r, String stage) {
        for (var rep : java.util.Arrays.asList(r.discovery(), r.delivery())) {
            if (rep == null) continue;
            for (StageResult s : rep.stages()) if (s.stageId().equals(stage)) return s.status();
        }
        return null;
    }

    @Test
    void requirementToApprovedCommitWithFullEvidence() throws Exception {
        ScriptedProvider p = model(false, PipelineEndToEndTest::goodImplementer);
        AgenticPipeline.Result r = pipeline(p, human("pat", id -> HumanChannel.Verdict.APPROVE, "")).run("e2e-ok", "Add a farewell greeting", "test");

        assertEquals("DELIVERED", r.status());
        r.delivery().stages().forEach(s -> assertEquals(StageStatus.SUCCEEDED, s.status(), s.stageId() + ": " + s.reason()));
        String log = TestRepo.git(repo, "log", "-1", "--format=%an%n%B", "agentic/e2e-ok");
        assertTrue(log.startsWith("agentic-sdlc\nfeat: add farewell greeting"), log);
        assertTrue(log.contains("Approved-by: release: approver=pat") && log.contains("Approved-by: plan: approver=pat"), log);
        assertTrue(TestRepo.git(repo, "show", "agentic/e2e-ok:" + GREETER).contains("farewell"), "the change is on the run branch");
        assertTrue(TestRepo.git(repo, "show", "agentic/e2e-ok:docs/changes/e2e-ok.md").contains("Farewell"), "docs are committed too");
        assertFalse(Files.readString(repo.resolve(GREETER)).contains("farewell"), "the main checkout is untouched");
        assertFalse(Files.exists(repo.resolve(".agentic/worktrees/e2e-ok")), "worktree removed, branch kept");
        assertEquals(List.of("approve-plan", "approve-release"), asked.stream().map(a -> a.replaceAll("-\\d+$", "")).toList());
        assertNull(r.pullRequest(), "pull requests are opt-in");
        assertTrue(Files.readString(r.runDir().resolve("artifacts/pull-request/pull-request.v1.json")).contains("\"status\" : \"SKIPPED\""));

        Path run = r.runDir();
        assertTrue(RunRecorder.verifyPersistedAudit(run.resolve("audit.jsonl")), "the persisted audit chain verifies");
        String audit = Files.readString(run.resolve("audit.jsonl"));
        assertEquals(p.sent.size(), audit.split("\"type\":\"LLM_CALL\"", -1).length - 1, "every model call is audited");
        assertTrue(audit.contains("provider=scripted"), "the scripted provider is labelled as such");
        JsonNode index = Json.parse("[" + String.join(",", Files.readAllLines(run.resolve("artifacts/index.jsonl"))) + "]");
        assertTrue(index.toString().contains("\"ref\":\"commit.json@1\""));
        assertTrue(Files.readString(run.resolve("summary.md")).contains("via provider(s) [scripted]"));
        JsonNode baseline = Json.parse(Files.readString(run.resolve("artifacts/baseline/delivery-baseline.v1.json")));
        assertEquals("plan.json@1", baseline.path("plan.json").path("ref").asText(), "delivery pinned the approved plan version");
        assertEquals(1, Json.parse(Files.readString(run.resolve("artifacts/verify/verification.v1.json"))).path("testsAdded").asInt());
        assertTrue(Files.isRegularFile(run.resolve("llm/transcripts/0001-analyze.json")));
    }

    @Test
    void aRejectedReleaseDiscardsTheChange() throws Exception {
        AgenticPipeline.Result r = pipeline(model(false, PipelineEndToEndTest::goodImplementer),
                human("pat", id -> id.startsWith("approve-release") ? HumanChannel.Verdict.REJECT : HumanChannel.Verdict.APPROVE, ""))
                .run("e2e-rej", "Add a farewell greeting", "test");

        assertEquals("REJECTED_AT_RELEASE", r.status());
        assertEquals(StageStatus.BLOCKED, status(r, "commit"));
        assertEquals(StageStatus.BLOCKED, status(r, "pull-request"), "nothing is pushed without a release approval");
        assertFalse(TestRepo.git(repo, "branch", "--list").contains("agentic/e2e-rej"), "the run branch is gone");
        assertTrue(Files.readString(r.runDir().resolve("audit.jsonl")).contains("ROLLBACK_COMPLETED"));
    }

    /** A bare repository as the "origin" remote, so pushes are real but stay local. */
    Path bareOrigin() throws Exception {
        Path remote = tmp.resolve("origin.git");
        TestRepo.git(tmp, "init", "-q", "--bare", remote.toString());
        TestRepo.git(repo, "remote", "add", "origin", remote.toString());
        return remote;
    }

    AgenticPipeline pipeline(ScriptedProvider p, HumanChannel h, PullRequests prs) {
        return new AgenticPipeline(config(), p, BUILD, inbox -> h, Clock.systemUTC(), new Redactor(Map.of()), prs);
    }

    @Test
    void anApprovedChangeIsPushedAndOpenedAsAPullRequestForHumanReview() throws Exception {
        Path origin = bareOrigin();
        try (FakeGitHub gh = new FakeGitHub(req -> new FakeGitHub.Response(201, "{\"number\":12,\"html_url\":\"https://github.com/o/r/pull/12\"}"))) {
            PullRequests prs = new PullRequests(true, "origin", "main", "o/r", gh.uri(), new PullRequests.Secret(PullRequestsTest.TOKEN));
            AgenticPipeline.Result r = pipeline(model(false, PipelineEndToEndTest::goodImplementer),
                    human("pat", id -> HumanChannel.Verdict.APPROVE, ""), prs).run("e2e-pr", "Add a farewell greeting", "test");

            assertEquals("DELIVERED", r.status());
            assertEquals("https://github.com/o/r/pull/12", r.pullRequest());
            assertEquals(r.commit(), TestRepo.git(origin, "rev-parse", "agentic/e2e-pr").strip(), "the approved commit was pushed");
            assertEquals(1, gh.requests.size(), "exactly one pull request, opened after the commit");
            JsonNode body = Json.parse(gh.requests.get(0).body());
            assertEquals("agentic/e2e-pr", body.path("head").asText());
            assertEquals("feat: add farewell greeting", body.path("title").asText());
            String description = body.path("body").asText();
            assertTrue(description.contains("# Release readiness") && description.contains("Approved: release: approver=pat"), description);
            assertTrue(description.contains("merging is your decision"), description);
            String audit = Files.readString(r.runDir().resolve("audit.jsonl"));
            assertTrue(audit.contains("opened pull request #12"), "the hand-off is audited");
            assertFalse(audit.contains(PullRequestsTest.TOKEN) || description.contains(PullRequestsTest.TOKEN), "the token never leaks");
            assertTrue(Files.readString(r.runDir().resolve("summary.md")).contains("**Pull request (human review and merge):** https://github.com/o/r/pull/12"));
        }
    }

    @Test
    void aFailedPullRequestKeepsTheApprovedCommit() throws Exception {
        bareOrigin();
        try (FakeGitHub gh = new FakeGitHub(req -> new FakeGitHub.Response(500, "{\"message\":\"Server Error\"}"))) {
            PullRequests prs = new PullRequests(true, "origin", "main", "o/r", gh.uri(), new PullRequests.Secret(PullRequestsTest.TOKEN));
            AgenticPipeline.Result r = pipeline(model(false, PipelineEndToEndTest::goodImplementer),
                    human("pat", id -> HumanChannel.Verdict.APPROVE, ""), prs).run("e2e-prf", "Add a farewell greeting", "test");

            assertEquals("DELIVERED_PR_FAILED", r.status());
            assertEquals(StageStatus.FAILED, status(r, "pull-request"));
            assertEquals(2, gh.requests.size(), "the hand-off is retried once (bounded)");
            assertNotNull(r.commit());
            assertEquals(r.commit(), TestRepo.git(repo, "rev-parse", "agentic/e2e-prf").strip(), "the approved commit is not rolled back");
            assertNull(r.pullRequest());
        }
    }

    @Test
    void aPlanRevisionRePlansWithTheReviewersFeedback() throws Exception {
        AtomicInteger planApprovals = new AtomicInteger();
        ScriptedProvider p = model(false, PipelineEndToEndTest::goodImplementer);
        AgenticPipeline.Result r = pipeline(p, human("pat", id -> id.startsWith("approve-plan") && planApprovals.incrementAndGet() == 1
                ? HumanChannel.Verdict.REVISE : HumanChannel.Verdict.APPROVE, "Name the method farewell, not goodbye."))
                .run("e2e-rev", "Add a farewell greeting", "test");

        assertEquals("DELIVERED", r.status());
        assertEquals(1, r.discovery().replans());
        List<Llm.Spec> plans = p.conversations.stream().filter(s -> s.role().equals("planner")).toList();
        assertEquals(2, plans.size(), "the planner ran again");
        assertTrue(p.sent.stream().anyMatch(b -> b.get(0) instanceof Llm.Text t && t.text().contains("Plan revision requested by pat")
                && t.text().contains("Name the method farewell, not goodbye.")), "the feedback is binding input to the re-plan");
        assertTrue(Files.readString(r.runDir().resolve("audit.jsonl")).contains("\"type\":\"REPLAN\""));
    }

    @Test
    void ambiguitiesAreResolvedByAPersonBeforePlanning() throws Exception {
        AgenticPipeline.Result r = pipeline(model(true, PipelineEndToEndTest::goodImplementer),
                human("pat", id -> HumanChannel.Verdict.APPROVE, "")).run("e2e-amb", "Make greetings friendlier", "test");

        assertEquals("DELIVERED", r.status());
        assertTrue(asked.get(0).startsWith("clarify"), "clarification comes first");
        String guidance = Files.readString(r.runDir().resolve("artifacts/clarify/guidance.v1.md"));
        assertTrue(guidance.contains("Resolved by **pat**") && guidance.contains("Approved assumption: use 'bye'"), guidance);
    }

    @Test
    void anAgentTamperingWithCiIsStoppedAndNothingIsDelivered() throws Exception {
        AgenticPipeline.Result r = pipeline(model(false, turn -> turn == 1
                        ? ScriptedProvider.call("write_file", Map.of("path", ".github/workflows/ci.yml", "content", "on: never"))
                        : ScriptedProvider.call("submit_implementation", Map.of("summary", "changed CI", "filesChanged",
                        List.of(".github/workflows/ci.yml"), "testsPassedLocally", false, "notes", ""))),
                human("pat", id -> HumanChannel.Verdict.APPROVE, "")).run("e2e-ci", "Add a farewell greeting", "test");

        assertNotEquals("DELIVERED", r.status());
        assertEquals(StageStatus.FAILED, status(r, "impl-T1"));
        assertEquals(StageStatus.BLOCKED, status(r, "release"));
        String audit = Files.readString(r.runDir().resolve("audit.jsonl"));
        assertTrue(audit.contains("CRITICAL") && audit.contains("protected path '.github/workflows/ci.yml'"));
        assertEquals("on: push\n", Files.readString(repo.resolve(".agentic/worktrees/e2e-ci/.github/workflows/ci.yml")).replace("\r", ""),
                "the CI file was never written (worktree kept for inspection)");
    }

    @Test
    void anAgentIdentityCannotApproveThePlan() throws Exception {
        AgenticPipeline.Result r = pipeline(model(false, PipelineEndToEndTest::goodImplementer),
                human("implementer", id -> HumanChannel.Verdict.APPROVE, "")).run("e2e-sod", "Add a farewell greeting", "test");

        assertEquals("STOPPED_IN_DISCOVERY_FAILED", r.status());
        assertEquals(StageStatus.REJECTED, status(r, "plan"));
        assertNull(r.delivery(), "nothing was implemented");
        assertTrue(Files.readString(r.runDir().resolve("audit.jsonl")).contains("separation-of-duties"));
    }
}
