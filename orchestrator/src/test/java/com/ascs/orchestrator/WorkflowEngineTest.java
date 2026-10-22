package com.ascs.orchestrator;

import com.ascs.Verifies;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.*;
import com.ascs.orchestrator.sdlc.SdlcWorkflow;
import com.ascs.orchestrator.sdlc.StubAgents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineTest {

    private final Clock clock = Clock.systemUTC();
    private ExecutorService pool;
    private ArtifactStore store;
    private AuditLog audit;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
        store = new ArtifactStore();
        audit = new AuditLog(clock);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private RunReport run(WorkflowGraph graph, Map<String, Agent> agents) {
        return new WorkflowEngine(graph, agents, store, audit, clock, pool).run("run-1", "build a url shortener");
    }

    private static Map<String, Agent> stubs() {
        return new HashMap<>(StubAgents.deterministic());
    }

    @Test
    @Verifies({"AC-7.1"})
    void happyPathSucceedsAndCommitsEveryArtifact() {
        RunReport report = run(SdlcWorkflow.standardGraph(), stubs());
        assertTrue(report.success());
        for (StageResult s : report.stages()) assertEquals(StageStatus.SUCCEEDED, s.status());
        assertEquals(7, store.latestAll().size());
    }

    @Test
    @Verifies({"AC-7.1"})
    void independentStagesActuallyRunConcurrently() {
        CountDownLatch barrier = new CountDownLatch(3);
        Map<String, Agent> agents = stubs();
        for (String id : List.of("testing", "security-review", "documentation")) {
            Agent delegate = agents.get(id);
            agents.put(id, input -> {
                barrier.countDown();
                if (!barrier.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("stages did not overlap");
                return delegate.execute(input);
            });
        }
        assertTrue(run(SdlcWorkflow.standardGraph(), agents).success());
    }

    @Test
    @Verifies({"AC-7.1"})
    void joinStageWaitsForAllParallelBranches() {
        List<String> events = new CopyOnWriteArrayList<>();
        Map<String, Agent> agents = stubs();
        for (String id : List.of("testing", "security-review", "documentation")) {
            Agent delegate = agents.get(id);
            agents.put(id, input -> {
                AgentResult r = delegate.execute(input);
                events.add("done:" + id);
                return r;
            });
        }
        Agent release = agents.get("release-readiness");
        agents.put("release-readiness", input -> {
            events.add("start:release");
            return release.execute(input);
        });

        assertTrue(run(SdlcWorkflow.standardGraph(), agents).success());
        int releaseStart = events.indexOf("start:release");
        assertEquals(4, events.size());
        for (String id : List.of("testing", "security-review", "documentation")) {
            assertTrue(events.indexOf("done:" + id) < releaseStart, id + " must finish before release starts");
        }
    }

    @Test
    void failedStageBlocksOnlyItsDescendants() {
        Map<String, Agent> agents = stubs();
        agents.put("testing", input -> { throw new IllegalStateException("boom"); });

        RunReport report = run(SdlcWorkflow.standardGraph(), agents);

        assertFalse(report.success());
        assertEquals(StageStatus.FAILED, report.status("testing"));
        assertEquals(StageStatus.SUCCEEDED, report.status("security-review")); // sibling branch unaffected
        assertEquals(StageStatus.SUCCEEDED, report.status("documentation"));
        assertEquals(StageStatus.BLOCKED, report.status("release-readiness"));
        assertTrue(store.latest("release-checklist.md").isEmpty());
    }

    @Test
    void deniedEntryGateBlocksStageWithoutInvokingAgent() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        WorkflowGraph g = new WorkflowGraph(List.of(
                StageDefinition.builder("a").exitGate(Gate.requireOutputs("a.txt")).build(),
                StageDefinition.builder("b").dependsOn("a")
                        .entryGate(Gate.requireArtifacts("never-produced.txt")).build()));
        Map<String, Agent> agents = Map.of(
                "a", input -> new AgentResult(Map.of("a.txt", "x"), "ok"),
                "b", input -> { invoked.set(true); return new AgentResult(Map.of(), "should not run"); });

        RunReport report = run(g, agents);

        assertEquals(StageStatus.SUCCEEDED, report.status("a"));
        assertEquals(StageStatus.BLOCKED, report.status("b"));
        assertFalse(invoked.get());
        assertTrue(audit.events().stream().anyMatch(e -> e.type() == AuditLog.Type.ENTRY_GATE_DENIED));
    }

    @Test
    void deniedExitGateFailsStageAndDoesNotCommitOutputs() {
        WorkflowGraph g = new WorkflowGraph(List.of(
                StageDefinition.builder("a").exitGate(Gate.requireOutputs("a.txt")).build(),
                StageDefinition.builder("b").dependsOn("a").build()));
        Map<String, Agent> agents = Map.of(
                "a", input -> new AgentResult(Map.of("a.txt", "   ", "extra.txt", "leak"), "blank output"),
                "b", input -> new AgentResult(Map.of("b.txt", "x"), "ok"));

        RunReport report = run(g, agents);

        assertEquals(StageStatus.FAILED, report.status("a"));
        assertEquals(StageStatus.BLOCKED, report.status("b"));
        assertTrue(store.latestAll().isEmpty(), "rejected outputs must not be committed");
    }

    @Test
    void throwingGateFailsClosed() {
        WorkflowGraph g = new WorkflowGraph(List.of(
                StageDefinition.builder("a")
                        .entryGate(Gate.of("broken", ctx -> { throw new IllegalStateException("bug"); })).build()));
        RunReport report = run(g, Map.of("a", input -> new AgentResult(Map.of("x", "y"), "ok")));
        assertEquals(StageStatus.BLOCKED, report.status("a"));
    }

    @Test
    void stageOnlySeesAncestorArtifactsNotSiblings() {
        List<List<String>> seen = new CopyOnWriteArrayList<>();
        Map<String, Agent> agents = stubs();
        for (String id : List.of("testing", "security-review", "documentation")) {
            Agent delegate = agents.get(id);
            agents.put(id, input -> {
                seen.add(input.upstream().stream().map(a -> a.name()).toList());
                return delegate.execute(input);
            });
        }
        assertTrue(run(SdlcWorkflow.standardGraph(), agents).success());
        assertEquals(3, seen.size());
        for (List<String> names : seen) {
            assertEquals(List.of("code.patch", "design.md", "requirements.md"), names);
        }
    }

    @Test
    void lineageTracesReleaseChecklistBackToRequirements() {
        assertTrue(run(SdlcWorkflow.standardGraph(), stubs()).success());
        List<String> lineage = new ArrayList<>();
        store.lineage(new ArtifactRef("release-checklist.md", 1)).forEach(a -> lineage.add(a.ref().toString()));

        assertEquals("requirements.md@1", lineage.get(0));                    // root first
        assertEquals("release-checklist.md@1", lineage.get(lineage.size() - 1)); // target last
        assertEquals(7, lineage.size());
    }

    @Test
    @Verifies({"AC-7.6"})
    void auditLogIsCompleteAndTamperEvident() {
        assertTrue(run(SdlcWorkflow.standardGraph(), stubs()).success());
        assertTrue(audit.verify());

        List<AuditLog.Event> events = new ArrayList<>(audit.events());
        assertEquals(AuditLog.Type.RUN_STARTED, events.get(0).type());
        assertEquals(AuditLog.Type.RUN_COMPLETED, events.get(events.size() - 1).type());

        AuditLog.Event victim = events.get(3);
        events.set(3, new AuditLog.Event(victim.seq(), victim.at(), victim.runId(), victim.stageId(),
                victim.type(), "tampered", victim.prevHash(), victim.hash()));
        assertFalse(AuditLog.verifyChain(events), "edited event must break the chain");

        List<AuditLog.Event> dropped = new ArrayList<>(audit.events());
        dropped.remove(5);
        assertFalse(AuditLog.verifyChain(dropped), "deleted event must break the chain");
    }

    @Test
    void engineRejectsGraphWithMissingAgent() {
        Map<String, Agent> agents = stubs();
        agents.remove("design");
        assertThrows(IllegalArgumentException.class, () -> run(SdlcWorkflow.standardGraph(), agents));
    }
}
