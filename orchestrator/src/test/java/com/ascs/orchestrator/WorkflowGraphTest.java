package com.ascs.orchestrator;

import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.StageDefinition;
import com.ascs.orchestrator.sdlc.SdlcWorkflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowGraphTest {

    private static StageDefinition stage(String id, String... deps) {
        return StageDefinition.builder(id).dependsOn(deps).build();
    }

    @Test
    void rejectsCycles() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new WorkflowGraph(List.of(stage("a", "c"), stage("b", "a"), stage("c", "b"))));
        assertTrue(ex.getMessage().contains("cycle"), ex.getMessage());
    }

    @Test
    void rejectsSelfDependencyUnknownDependencyAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of(stage("a", "a"))));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of(stage("a", "ghost"))));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(List.of(stage("a"), stage("a"))));
    }

    @Test
    void standardGraphHasExpectedParallelWaves() {
        WorkflowGraph g = SdlcWorkflow.standardGraph();
        assertEquals(5, g.levels().size());
        assertEquals(Set.of("testing", "security-review", "documentation"), Set.copyOf(g.levels().get(3)));
        assertEquals(List.of("release-readiness"), g.levels().get(4));
    }

    @Test
    void topologicalOrderRespectsDependencies() {
        WorkflowGraph g = SdlcWorkflow.standardGraph();
        List<String> order = g.topologicalOrder();
        for (StageDefinition d : g.stages()) {
            for (String dep : d.dependsOn()) {
                assertTrue(order.indexOf(dep) < order.indexOf(d.id()), dep + " must precede " + d.id());
            }
        }
    }

    @Test
    void computesAncestors() {
        WorkflowGraph g = SdlcWorkflow.standardGraph();
        assertEquals(Set.of("requirements", "design", "implementation", "testing",
                "security-review", "documentation"), g.ancestors("release-readiness"));
        assertTrue(g.ancestors("requirements").isEmpty());
    }
}
