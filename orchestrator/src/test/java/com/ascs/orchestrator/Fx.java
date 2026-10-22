package com.ascs.orchestrator;

import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** Tiny fixtures: stages named x produce "x.txt". */
final class Fx {
    private Fx() {}

    static StageDefinition.Builder stage(String id, String... deps) {
        return StageDefinition.builder(id).dependsOn(deps).exitGate(Gate.requireOutputs(id + ".txt"));
    }

    static Agent ok(String id) {
        return in -> new AgentResult(Map.of(id + ".txt", id + " output, attempt " + in.attempt()), id + " done");
    }

    static Agent boom(String msg) {
        return in -> { throw new IllegalStateException(msg); };
    }

    static WorkflowGraph graph(StageDefinition... defs) {
        return new WorkflowGraph(List.of(defs));
    }

    static WorkflowEngine.Builder engine(WorkflowGraph g, Map<String, Agent> agents, ExecutorService pool) {
        return WorkflowEngine.builder(g, agents, pool);
    }

    static long count(AuditLog audit, AuditLog.Type type) {
        return audit.events().stream().filter(e -> e.type() == type).count();
    }
}
