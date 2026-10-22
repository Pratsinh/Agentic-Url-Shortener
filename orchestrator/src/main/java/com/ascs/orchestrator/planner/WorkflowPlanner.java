package com.ascs.orchestrator.planner;

import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.Gate;
import com.ascs.orchestrator.model.RetryPolicy;
import com.ascs.orchestrator.model.RollbackAction;
import com.ascs.orchestrator.model.StageDefinition;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns a task decomposition into a validated workflow DAG with entry/exit gates derived from artifact flow. */
public final class WorkflowPlanner {

    private final Duration retryBackoff;

    public WorkflowPlanner(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    /** @param rollbacks compensating actions by task id; compensable tasks without one get a no-op compensation */
    public WorkflowGraph plan(List<Task> tasks, Map<String, RollbackAction> rollbacks) {
        Map<String, Task> byId = new LinkedHashMap<>();
        for (Task t : tasks) {
            if (byId.putIfAbsent(t.id(), t) != null) throw new IllegalArgumentException("duplicate task " + t.id());
        }
        return new WorkflowGraph(tasks.stream().map(t -> {
            for (String dep : t.dependsOn()) {
                if (!byId.containsKey(dep)) throw new IllegalArgumentException("task '" + t.id() + "' depends on unknown task '" + dep + "'");
            }
            StageDefinition.Builder b = StageDefinition.builder(t.id())
                    .describe(t.title())
                    .dependsOn(t.dependsOn().toArray(new String[0]))
                    .exitGate(Gate.requireOutputs(t.output()))
                    .retry(RetryPolicy.of(t.maxAttempts(), retryBackoff))
                    .impact(t.impact())
                    .onFailure(t.onFailure());
            if (!t.dependsOn().isEmpty()) {
                String[] inputs = t.dependsOn().stream().map(d -> byId.get(d).output()).collect(Collectors.toList()).toArray(new String[0]);
                b.entryGate(Gate.requireArtifacts(inputs));
            }
            if (t.requiresApproval()) b.requireApproval();
            if (t.fallbackAgent() != null) b.fallback(t.fallbackAgent());
            if (t.compensable()) b.rollback(rollbacks.getOrDefault(t.id(), ctx -> { }));
            return b.build();
        }).toList());
    }
}
