package com.ascs.orchestrator;

import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.*;
import com.ascs.orchestrator.policy.PolicyEngine;
import com.ascs.orchestrator.policy.SecretLeakPolicy;
import com.ascs.orchestrator.sdlc.SdlcWorkflow;
import com.ascs.orchestrator.sdlc.StubAgents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Change-driven dynamic re-planning on the standard SDLC graph. */
class ReplanTest {

    private ExecutorService pool;
    private ArtifactStore store;
    private AuditLog audit;
    private Metrics metrics;
    private final Map<String, AtomicInteger> invocations = new HashMap<>();
    private final AtomicReference<WorkflowEngine> engineRef = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(6);
        store = new ArtifactStore();
        audit = new AuditLog(Clock.systemUTC());
        metrics = new Metrics();
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    /** Standard graph + stub agents, each counted; 'testing' runs {@code onFirstTesting} once, during its first run. */
    private Map<String, Agent> agents(java.util.function.Consumer<WorkflowEngine> onFirstTesting) {
        Map<String, Agent> agents = new HashMap<>(StubAgents.deterministic());
        AtomicBoolean fired = new AtomicBoolean();
        for (String id : List.copyOf(agents.keySet())) {
            Agent delegate = agents.get(id);
            AtomicInteger counter = new AtomicInteger();
            invocations.put(id, counter);
            agents.put(id, in -> {
                counter.incrementAndGet();
                if (id.equals("testing") && fired.compareAndSet(false, true)) onFirstTesting.accept(engineRef.get());
                return delegate.execute(in);
            });
        }
        return agents;
    }

    private WorkflowEngine engine(Map<String, Agent> agents, RunOptions options, PolicyEngine policies) {
        WorkflowEngine e = Fx.engine(SdlcWorkflow.standardGraph(), agents, pool).store(store).audit(audit)
                .metrics(metrics).options(options).policies(policies).build();
        engineRef.set(e);
        return e;
    }

    @Test
    void upstreamChangeReExecutesOnlyTheStagesThatConsumedIt() {
        var agents = agents(e -> e.submitChange("design.md", "# Design v2\nuse HMAC-signed codes", "architect", "review comment"));
        RunReport r = engine(agents, RunOptions.defaults(), PolicyEngine.none()).run("r1", "build a url shortener");

        assertTrue(r.success(), r.stages().toString());
        assertEquals(1, r.replans());
        // Minimal re-execution: stages upstream of the change are untouched...
        assertEquals(1, invocations.get("requirements").get());
        assertEquals(1, invocations.get("design").get());
        // ...stages that consumed design.md@1 re-ran.
        assertEquals(2, invocations.get("implementation").get());
        assertEquals(2, invocations.get("testing").get());

        assertEquals(2, store.history("design.md").size());
        assertTrue(store.latest("design.md").get().content().contains("HMAC"));
        assertTrue(store.isRevoked(new ArtifactRef("code.patch", 1)), "stale implementation output must be revoked");

        List<String> releaseInputs = store.latest("release-checklist.md").get().inputs().stream().map(ArtifactRef::toString).toList();
        assertTrue(releaseInputs.contains("design.md@2"), releaseInputs.toString());
        assertFalse(releaseInputs.contains("design.md@1"), "release must be derived from the revised design only");

        assertEquals(1, Fx.count(audit, AuditLog.Type.CHANGE_APPLIED));
        assertEquals(1, Fx.count(audit, AuditLog.Type.REPLAN));
        assertTrue(Fx.count(audit, AuditLog.Type.STAGE_INVALIDATED) >= 2);
        assertEquals(1, metrics.snapshot().replans());
        assertTrue(audit.verify());
    }

    @Test
    void replanBudgetStopsRequirementThrash() {
        AtomicBoolean changing = new AtomicBoolean();
        java.util.concurrent.CountDownLatch bothQueued = new java.util.concurrent.CountDownLatch(1);
        var agents = agents(e -> {
            changing.set(true);
            e.submitChange("design.md", "# Design v2", "pm", "tweak 1");
            e.submitChange("design.md", "# Design v3", "pm", "tweak 2");
            bothQueued.countDown();
        });
        // Re-runs triggered by the first change must not finish the run before the second change is queued
        // (a descheduled worker thread between the two submissions would otherwise let the run complete first).
        agents.replaceAll((id, delegate) -> in -> {
            if (changing.get()) assertTrue(bothQueued.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return delegate.execute(in);
        });
        RunReport r = engine(agents, RunOptions.defaults().withMaxReplans(1), PolicyEngine.none()).run("r1", "req");

        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(1, Fx.count(audit, AuditLog.Type.CHANGE_REJECTED));
        assertEquals(2, store.history("design.md").size()); // v1 + the one accepted change
    }

    @Test
    void changeThatViolatesPolicyIsRejectedAndNothingIsReplanned() {
        // review:accepted CR-01 AWS documented example key id: a change request that policy must reject
        var agents = agents(e -> e.submitChange("design.md", "# Design\naws_key=AKIAIOSFODNN7EXAMPLE", "intern", "oops"));
        RunReport r = engine(agents, RunOptions.defaults(), PolicyEngine.of(new SecretLeakPolicy())).run("r1", "req");

        assertTrue(r.success());
        assertEquals(0, r.replans());
        assertEquals(1, store.history("design.md").size());
        assertEquals(1, Fx.count(audit, AuditLog.Type.CHANGE_REJECTED));
        assertEquals(1, invocations.get("implementation").get());
    }

    @Test
    void identicalAndUnknownChangesAreHarmless() {
        var agents = agents(e -> {
            e.submitChange("design.md", store.latest("design.md").get().content(), "pm", "no-op");
            e.submitChange("does-not-exist.md", "x", "pm", "typo");
        });
        RunReport r = engine(agents, RunOptions.defaults(), PolicyEngine.none()).run("r1", "req");

        assertTrue(r.success());
        assertEquals(0, r.replans());
        assertEquals(1, Fx.count(audit, AuditLog.Type.CHANGE_NOOP));
        assertEquals(1, Fx.count(audit, AuditLog.Type.CHANGE_REJECTED));
    }

    @Test
    void reRunStagesPassApprovalAgainAfterAReplan() {
        // implementation needs approval; a design change after it was approved must re-open the approval.
        AtomicInteger approvals = new AtomicInteger();
        var gateway = new com.ascs.orchestrator.approval.ScriptedApprovalGateway(req -> {
            approvals.incrementAndGet();
            return Decision.approve("lead", "ok #" + approvals.get());
        });
        var graph = new com.ascs.orchestrator.core.WorkflowGraph(List.of(
                Fx.stage("design").build(),
                Fx.stage("implementation", "design").impact(Impact.HIGH).requireApproval().build(),
                Fx.stage("testing", "implementation").build()));
        AtomicBoolean fired = new AtomicBoolean();
        Map<String, Agent> agents = new HashMap<>();
        agents.put("design", Fx.ok("design"));
        agents.put("implementation", Fx.ok("implementation"));
        agents.put("testing", in -> {
            if (fired.compareAndSet(false, true)) engineRef.get().submitChange("design.txt", "revised design", "arch", "late finding");
            return Fx.ok("testing").execute(in);
        });
        WorkflowEngine e = Fx.engine(graph, agents, pool).store(store).audit(audit).metrics(metrics).approvals(gateway).build();
        engineRef.set(e);

        RunReport r = e.run("r1", "req");

        assertTrue(r.success());
        assertEquals(2, approvals.get(), "re-run of a HIGH-impact stage must be approved again");
    }
}
