package com.ascs.orchestrator;

import com.ascs.Verifies;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded retries, fallback, timeouts, and human approval. */
class ResilienceTest {

    private ExecutorService pool;
    private ArtifactStore store;
    private AuditLog audit;
    private Metrics metrics;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
        store = new ArtifactStore();
        audit = new AuditLog(Clock.systemUTC());
        metrics = new Metrics();
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private WorkflowEngine.Builder eng(com.ascs.orchestrator.core.WorkflowGraph g, Map<String, Agent> agents) {
        return Fx.engine(g, agents, pool).store(store).audit(audit).metrics(metrics);
    }

    // ---- retries -----------------------------------------------------------------------------
    @Test
    @Verifies({"AC-7.2"})
    void transientFailureIsRetriedAndRecoveryIsMeasured() {
        var g = Fx.graph(Fx.stage("a").retry(RetryPolicy.of(3, Duration.ofMillis(10))).build());
        Agent flaky = in -> {
            if (in.attempt() < 3) throw new IllegalStateException("transient " + in.attempt());
            return new AgentResult(Map.of("a.txt", "ok"), "recovered");
        };
        RunReport r = eng(g, Map.of("a", flaky)).build().run("r1", "req");

        assertTrue(r.success());
        assertEquals(3, r.result("a").attempts());
        Metrics.Snapshot m = metrics.snapshot();
        assertEquals(2, m.retries());
        assertEquals(3, m.attempts());
        assertEquals(1, m.recoveries());
        assertTrue(m.mttrMs() >= 10, "MTTR should include backoff, was " + m.mttrMs());
    }

    @Test
    @Verifies({"AC-7.2"})
    void retriesAreBounded() {
        AtomicInteger calls = new AtomicInteger();
        var g = Fx.graph(Fx.stage("a").retry(RetryPolicy.of(2, Duration.ZERO)).build(), Fx.stage("b", "a").build());
        Agent alwaysFails = in -> { calls.incrementAndGet(); throw new IllegalStateException("down"); };

        RunReport r = eng(g, Map.of("a", alwaysFails, "b", Fx.ok("b"))).build().run("r1", "req");

        assertEquals(2, calls.get());
        assertEquals(StageStatus.FAILED, r.status("a"));
        assertEquals(StageStatus.BLOCKED, r.status("b"));
        assertEquals(RunOutcome.FAILED, r.outcome());
    }

    @Test
    @Verifies({"AC-7.2"})
    void fallbackAgentRescuesAStageAfterRetriesAreExhausted() {
        AtomicInteger primaryCalls = new AtomicInteger();
        var g = Fx.graph(Fx.stage("a").retry(RetryPolicy.of(2, Duration.ZERO)).fallback("a-fallback").build());
        Map<String, Agent> agents = new HashMap<>();
        agents.put("a", in -> { primaryCalls.incrementAndGet(); throw new IllegalStateException("primary down"); });
        agents.put("a-fallback", Fx.ok("a"));

        RunReport r = eng(g, agents).build().run("r1", "req");

        assertTrue(r.success());
        assertTrue(r.result("a").usedFallback());
        assertEquals(2, primaryCalls.get());
        assertEquals(1, Fx.count(audit, AuditLog.Type.FALLBACK_USED));
        assertEquals(1, metrics.snapshot().fallbacks());
    }

    @Test
    void failingFallbackFailsTheStage() {
        var g = Fx.graph(Fx.stage("a").fallback("fb").build());
        RunReport r = eng(g, Map.of("a", Fx.boom("x"), "fb", Fx.boom("y"))).build().run("r1", "req");
        assertEquals(StageStatus.FAILED, r.status("a"));
        assertTrue(store.latestAll().isEmpty());
    }

    @Test
    void hungAgentTimesOutWithoutHangingTheRun() {
        var g = Fx.graph(Fx.stage("a").build());
        Agent hung = in -> { Thread.sleep(10_000); return new AgentResult(Map.of("a.txt", "late"), "late"); };
        RunReport r = eng(g, Map.of("a", hung))
                .options(RunOptions.defaults().withStageTimeout(Duration.ofMillis(150))).build().run("r1", "req");

        assertEquals(StageStatus.FAILED, r.status("a"));
        assertTrue(r.result("a").reason().contains("timed out"), r.result("a").reason());
        assertTrue(r.latency().toSeconds() < 5, "run must not wait for the hung agent");
        assertTrue(store.latestAll().isEmpty(), "a timed-out attempt must not commit anything");
    }

    // ---- human approval ----------------------------------------------------------------------
    @Test
    @Verifies({"AC-7.3"})
    void outputIsCommittedOnlyAfterHumanApproval() {
        AtomicBoolean committedBeforeDecision = new AtomicBoolean(true);
        ScriptedApprovalGateway gateway = new ScriptedApprovalGateway(req -> {
            committedBeforeDecision.set(store.latest("a.txt").isPresent()); // reviewer sees an UNCOMMITTED proposal
            return Decision.approve("alice", "looks right");
        });
        var g = Fx.graph(Fx.stage("a").impact(Impact.HIGH).requireApproval().build(), Fx.stage("b", "a").build());

        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.ok("b"))).approvals(gateway).build().run("r1", "req");

        assertTrue(r.success());
        assertFalse(committedBeforeDecision.get());
        assertEquals(1, gateway.requests().size());
        assertEquals(Impact.HIGH, gateway.requests().get(0).impact());
        assertTrue(gateway.requests().get(0).proposedOutputs().containsKey("a.txt"));
        assertTrue(audit.events().stream().anyMatch(e -> e.type() == AuditLog.Type.APPROVAL_GRANTED
                && e.detail().contains("alice")));
        assertEquals(1, metrics.snapshot().approvalsGranted());
    }

    @Test
    @Verifies({"AC-7.3"})
    void rejectionCommitsNothingAndBlocksDescendants() {
        var g = Fx.graph(Fx.stage("a").impact(Impact.HIGH).requireApproval().build(), Fx.stage("b", "a").build());
        var gateway = new ScriptedApprovalGateway(req -> Decision.reject("bob", "wrong approach"));

        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.ok("b"))).approvals(gateway).build().run("r1", "req");

        assertEquals(StageStatus.REJECTED, r.status("a"));
        assertEquals(StageStatus.BLOCKED, r.status("b"));
        assertEquals(RunOutcome.FAILED, r.outcome());
        assertTrue(store.latestAll().isEmpty());
        assertTrue(r.result("a").reason().contains("bob"));
    }

    @Test
    @Verifies({"AC-7.3"})
    void unansweredApprovalFailsClosed() {
        var g = Fx.graph(Fx.stage("a").impact(Impact.HIGH).requireApproval().build());
        RunReport r = eng(g, Map.of("a", Fx.ok("a"))).approvals(ScriptedApprovalGateway.silent())
                .options(RunOptions.defaults().withApprovalTimeout(Duration.ofMillis(150))).build().run("r1", "req");

        assertEquals(StageStatus.REJECTED, r.status("a"));
        assertTrue(r.result("a").reason().contains("timed out"));
        assertTrue(store.latestAll().isEmpty());
        assertEquals(1, metrics.snapshot().approvalsTimedOut());
    }

    @Test
    void approvalDoesNotBlockIndependentBranches() {
        // 'slow-review' waits for a human; 'other' must still finish in the meantime.
        var g = Fx.graph(Fx.stage("slow-review").impact(Impact.HIGH).requireApproval().build(), Fx.stage("other").build());
        RunReport r = eng(g, Map.of("slow-review", Fx.ok("slow-review"), "other", Fx.ok("other")))
                .approvals(ScriptedApprovalGateway.silent())
                .options(RunOptions.defaults().withApprovalTimeout(Duration.ofMillis(200))).build().run("r1", "req");

        assertEquals(StageStatus.SUCCEEDED, r.status("other"));
        assertEquals(StageStatus.REJECTED, r.status("slow-review"));
    }

    @Test
    void misconfiguredGovernanceIsRefusedBeforeRunning() {
        var highWithoutApproval = Fx.graph(Fx.stage("a").impact(Impact.HIGH).build());
        assertThrows(IllegalArgumentException.class,
                () -> eng(highWithoutApproval, Map.of("a", Fx.ok("a"))).build());

        var approvalWithoutGateway = Fx.graph(Fx.stage("a").requireApproval().build());
        assertThrows(IllegalArgumentException.class,
                () -> eng(approvalWithoutGateway, Map.of("a", Fx.ok("a"))).build());

        var missingFallback = Fx.graph(Fx.stage("a").fallback("ghost").build());
        assertThrows(IllegalArgumentException.class,
                () -> eng(missingFallback, Map.of("a", Fx.ok("a"))).build());
    }

    @Test
    void engineInstanceRunsOnlyOnce() {
        var g = Fx.graph(Fx.stage("a").build());
        WorkflowEngine e = eng(g, Map.of("a", Fx.ok("a"))).build();
        e.run("r1", "req");
        assertThrows(IllegalStateException.class, () -> e.run("r2", "req"));
    }
}
