package com.ascs.orchestrator.sdlc;

import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.ArtifactRef;
import com.ascs.orchestrator.model.RunReport;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runnable demo (no Spring, no Maven needed): drives the standard SDLC graph with stub agents. */
public final class OrchestratorDemo {
    private OrchestratorDemo() {}

    public static void main(String[] args) {
        String requirement = args.length > 0 ? String.join(" ", args)
                : "Build a URL shortener with create, redirect, expiry and click analytics";
        Clock clock = Clock.systemUTC();
        WorkflowGraph graph = SdlcWorkflow.standardGraph();
        ArtifactStore store = new ArtifactStore();
        AuditLog audit = new AuditLog(clock);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            System.out.println("Parallel waves: " + graph.levels());
            RunReport report = new WorkflowEngine(graph, StubAgents.deterministic(), store, audit, clock, pool)
                    .run("demo-1", requirement);

            System.out.println("\nSTAGE                STATUS      MS");
            report.stages().forEach(s -> System.out.printf("%-20s %-11s %d%n",
                    s.stageId(), s.status(), s.duration().toMillis()));
            System.out.println("\nrun success=" + report.success() + " latencyMs=" + report.latency().toMillis());

            System.out.println("\nLineage of release-checklist.md:");
            store.lineage(new ArtifactRef("release-checklist.md", 1))
                    .forEach(a -> System.out.println("  " + a.ref() + " <- " + a.inputs()));

            System.out.println("\nAudit events=" + audit.events().size() + " chainIntact=" + audit.verify());
            audit.events().forEach(e -> System.out.printf("  #%02d %-18s %-18s %s%n",
                    e.seq(), e.type(), e.stageId() == null ? "-" : e.stageId(), e.detail()));
        } finally {
            pool.shutdownNow();
        }
    }
}
