package com.ascs.orchestrator;

import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.Impact;
import com.ascs.orchestrator.model.StageDefinition;
import com.ascs.orchestrator.planner.NormalizedRequirement;
import com.ascs.orchestrator.planner.NormalizedRequirement.Kind;
import com.ascs.orchestrator.planner.RuleBasedRequirementAnalyzer;
import com.ascs.orchestrator.planner.Task;
import com.ascs.orchestrator.planner.WorkflowPlanner;
import com.ascs.orchestrator.sdlc.StubAgents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    private final RuleBasedRequirementAnalyzer analyzer = new RuleBasedRequirementAnalyzer();
    private final WorkflowPlanner planner = new WorkflowPlanner(Duration.ZERO);

    @Test
    void classifiesRequirements() {
        assertEquals(Kind.GREENFIELD, analyzer.analyze("Build a URL shortener from scratch with expiry").kind());
        assertEquals(Kind.BROWNFIELD, analyzer.analyze("Replace the in-memory repository with Cassandra").kind());
        NormalizedRequirement vague = analyzer.analyze("Make the links more secure and faster");
        assertEquals(Kind.AMBIGUOUS, vague.kind());
        assertEquals(2, vague.ambiguities().size());
        assertEquals(vague.ambiguities().size(), vague.assumptions().size(), "every question carries a proposed default");
    }

    @Test
    void aQuantifiedGoalIsNotAmbiguous() {
        NormalizedRequirement n = analyzer.analyze("Make redirects faster: p99 under 50 ms at 1000 rps");
        assertTrue(n.ambiguities().isEmpty());
        assertTrue(n.kind() != Kind.AMBIGUOUS);
    }

    @Test
    void everyDecompositionIsAValidDagWithUniqueOutputsAndHighImpactApprovals() {
        for (String text : List.of("Build a URL shortener", "Replace the storage layer", "Make it secure and faster")) {
            NormalizedRequirement n = analyzer.analyze(text);
            WorkflowGraph graph = planner.plan(n.tasks(), Map.of());

            Set<String> outputs = new HashSet<>();
            for (Task t : n.tasks()) {
                assertTrue(outputs.add(t.output()), "duplicate output " + t.output() + " in " + n.kind());
                if (t.impact() == Impact.HIGH) assertTrue(t.requiresApproval());
            }
            // the planned graph is accepted by the engine's governance validation
            var pool = Executors.newFixedThreadPool(1);
            try {
                assertNotNull(com.ascs.orchestrator.core.WorkflowEngine.builder(graph, StubAgents.forTasks(n.tasks()), pool)
                        .approvals(ScriptedApprovalGateway.approveAll("a", "ok")).build());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void greenfieldHasParallelDeliveryAndAJoin() {
        NormalizedRequirement n = analyzer.analyze("Build a URL shortener");
        WorkflowGraph g = planner.plan(n.tasks(), Map.of());
        assertEquals(Set.of("unit-tests", "integration-tests", "security-review", "documentation"), Set.copyOf(g.levels().get(3)));
        assertEquals(Set.of("unit-tests", "integration-tests", "security-review", "documentation"),
                g.stage("release-readiness").dependsOn());
        assertEquals(1, g.stage("release-readiness").entryGates().size(), "entry gate derived from artifact flow");
        assertTrue(g.stage("release-readiness").requiresApproval());
    }

    @Test
    void ambiguousPlanGatesDesignBehindHumanClarification() {
        NormalizedRequirement n = analyzer.analyze("Make the links more secure and faster");
        WorkflowGraph g = planner.plan(n.tasks(), Map.of());
        assertEquals(Set.of("clarification"), g.stage("design").dependsOn());
        StageDefinition clarification = g.stage("clarification");
        assertTrue(clarification.requiresApproval());
        assertEquals(Impact.HIGH, clarification.impact());
    }

    @Test
    void brownfieldPlanAnalyzesImpactFirstAndRollsBackOnRegressionFailure() {
        WorkflowGraph g = planner.plan(analyzer.analyze("Replace the storage layer").tasks(), Map.of());
        assertEquals(Set.of("requirements"), g.stage("impact-analysis").dependsOn());
        assertEquals(Set.of("impact-analysis"), g.stage("design").dependsOn());
        assertEquals(com.ascs.orchestrator.model.FailureStrategy.ROLLBACK_RUN, g.stage("regression-tests").onFailure());
        assertNotNull(g.stage("implement-change").rollback());
    }

    @Test
    void plannerRejectsBrokenDecompositions() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(
                List.of(Task.of("a", "a", "a.md", "ghost")), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(
                List.of(Task.of("a", "a", "a.md"), Task.of("a", "dup", "b.md")), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(
                List.of(Task.of("a", "a", "a.md", "b"), Task.of("b", "b", "b.md", "a")), Map.of()));
    }
}
