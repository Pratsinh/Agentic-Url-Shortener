package com.ascs.orchestrator;

import com.ascs.Verifies;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Rollback, safe-stop (kill switch, failure budget) and their interaction with in-flight work. */
class RollbackSafeStopTest {

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

    @Test
    @Verifies({"AC-7.5"})
    void rollbackRunCompensatesCompletedStagesInReverseOrderAndRevokesOutputs() {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<List<ArtifactRef>> seenByB = new AtomicReference<>();
        var g = Fx.graph(
                Fx.stage("a").rollback(ctx -> order.add("a")).build(),
                Fx.stage("b", "a").rollback(ctx -> { order.add("b"); seenByB.set(ctx.artifacts()); }).build(),
                Fx.stage("c", "b").onFailure(FailureStrategy.ROLLBACK_RUN).build());

        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.ok("b"), "c", Fx.boom("deploy failed"))).build().run("r1", "req");

        assertEquals(RunOutcome.ROLLED_BACK, r.outcome());
        assertEquals(List.of("b", "a"), order, "dependents must be undone before their dependencies");
        assertEquals(StageStatus.ROLLED_BACK, r.status("a"));
        assertEquals(StageStatus.ROLLED_BACK, r.status("b"));
        assertEquals(StageStatus.FAILED, r.status("c"));
        assertEquals("[b.txt@1]", seenByB.get().toString());
        assertTrue(store.latestAll().isEmpty(), "rolled-back outputs must no longer be visible");
        assertTrue(store.isRevoked(new ArtifactRef("a.txt", 1)));
        assertEquals(2, metrics.snapshot().rollbacks());
        assertEquals(1.0, metrics.snapshot().rollbackRate());
    }

    @Test
    @Verifies({"AC-7.5"})
    void failedCompensationIsEscalatedNotHidden() {
        var g = Fx.graph(
                Fx.stage("a").rollback(ctx -> { throw new IllegalStateException("cannot revert"); }).build(),
                Fx.stage("b", "a").onFailure(FailureStrategy.ROLLBACK_RUN).build());

        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.boom("x"))).build().run("r1", "req");

        assertEquals(RunOutcome.ROLLBACK_INCOMPLETE, r.outcome());
        assertEquals(StageStatus.ROLLBACK_FAILED, r.status("a"));
        assertEquals(1, metrics.snapshot().rollbackFailures());
        assertEquals(1, Fx.count(audit, AuditLog.Type.ROLLBACK_FAILED));
    }

    @Test
    void rollbackCancelsInFlightSiblings() {
        var g = Fx.graph(Fx.stage("a").build(), Fx.stage("bad", "a").onFailure(FailureStrategy.ROLLBACK_RUN).build(),
                Fx.stage("slow", "a").build());
        Agent slow = in -> { Thread.sleep(10_000); return new AgentResult(Map.of("slow.txt", "late"), "late"); };

        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "bad", Fx.boom("x"), "slow", slow)).build().run("r1", "req");

        assertEquals(RunOutcome.ROLLED_BACK, r.outcome());
        assertEquals(StageStatus.BLOCKED, r.status("slow"));
        assertTrue(r.latency().toSeconds() < 5);
    }

    @Test
    void safeStopKeepsCompletedWorkAndDoesNotRollBack() {
        List<String> compensated = new CopyOnWriteArrayList<>();
        var g = Fx.graph(
                Fx.stage("a").rollback(ctx -> compensated.add("a")).build(),
                Fx.stage("b", "a").onFailure(FailureStrategy.SAFE_STOP).build());

        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.boom("x"))).build().run("r1", "req");

        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(StageStatus.SUCCEEDED, r.status("a")); // state is frozen for a human, not undone
        assertTrue(compensated.isEmpty());
        assertTrue(store.latest("a.txt").isPresent());
    }

    @Test
    void killSwitchCancelsInFlightWorkAndCommitsNothing() {
        AtomicReference<WorkflowEngine> ref = new AtomicReference<>();
        var g = Fx.graph(Fx.stage("a").build(), Fx.stage("b", "a").build());
        Agent pullsTheCord = in -> {
            ref.get().requestSafeStop("operator pressed the kill switch");
            Thread.sleep(10_000);
            return new AgentResult(Map.of("a.txt", "never committed"), "late");
        };
        WorkflowEngine engine = eng(g, Map.of("a", pullsTheCord, "b", Fx.ok("b"))).build();
        ref.set(engine);

        RunReport r = engine.run("r1", "req");

        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(StageStatus.BLOCKED, r.status("a"));
        assertEquals(StageStatus.BLOCKED, r.status("b"));
        assertTrue(store.latestAll().isEmpty());
        assertTrue(r.latency().toSeconds() < 5);
    }

    @Test
    void failureBudgetTripsSafeStop() {
        var g = Fx.graph(Fx.stage("x").build(), Fx.stage("y").build(), Fx.stage("z").build());
        Map<String, Agent> agents = new HashMap<>();
        for (String id : List.of("x", "y", "z")) agents.put(id, Fx.boom("down"));

        RunReport r = eng(g, agents).options(RunOptions.defaults().withFailureBudget(2)).build().run("r1", "req");

        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        long failed = r.stages().stream().filter(s -> s.status() == StageStatus.FAILED).count();
        assertTrue(failed >= 2, "budget trips only after two permanent failures");
    }
}
