package com.ascs.orchestrator.agents;

import com.ascs.orchestrator.approval.ApprovalGateway;
import com.ascs.orchestrator.approval.ConsoleApprovalGateway;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.Decision;
import com.ascs.orchestrator.model.RunReport;
import com.ascs.orchestrator.model.StageResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the agentic delivery pipeline against this repository and writes every committed artifact, plus a run
 * record built from the audit log, to an output directory.
 * Usage: DeliveryRunner [projectRoot] [--out docs/delivery] [--jacoco module/target/site/jacoco/jacoco.xml]... [--target 100] [--interactive]
 * (without --jacoco, every module's report is read and merged)
 */
public final class DeliveryRunner {
    private DeliveryRunner() {}

    public record Result(RunReport report, Map<String, String> artifacts, List<AuditLog.Event> audit, boolean auditIntact) {}

    /** Approves unless the release agent itself recommends against releasing. */
    public static ApprovalGateway scriptedReleaseManager() {
        return new ScriptedApprovalGateway(r -> r.proposedOutputs().getOrDefault(ReleaseReadinessAgent.OUTPUT, "").contains("NOT READY")
                ? Decision.reject("release-manager", "code review not approved")
                : Decision.approve("release-manager", "review verdict and QA evidence inspected"));
    }

    public static Result run(Path root, List<Path> jacocoXmls, double target, CommandRunner git, ApprovalGateway approvals) {
        AuditLog audit = new AuditLog(Clock.systemUTC());
        ArtifactStore store = new ArtifactStore();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            RunReport report = WorkflowEngine.builder(DeliveryPipeline.graph(), DeliveryPipeline.agents(root, jacocoXmls, git, target), pool)
                    .store(store).audit(audit).policies(DeliveryPipeline.policies()).approvals(approvals).build()
                    .run("delivery-1", DeliveryPipeline.REQUIREMENT);
            Map<String, String> artifacts = new LinkedHashMap<>();
            for (Artifact a : store.latestAll()) artifacts.put(a.name(), a.content());
            return new Result(report, artifacts, audit.events(), audit.verify());
        } finally {
            pool.shutdownNow();
        }
    }

    /** The run as markdown: stage outcomes and the full audit trail. */
    public static String runRecord(Result r) {
        StringBuilder md = new StringBuilder("# Delivery pipeline run\n\n")
                .append("**Requirement:** \"").append(DeliveryPipeline.REQUIREMENT).append("\"\n\n")
                .append("**Outcome:** ").append(r.report().outcome()).append(" in ").append(r.report().latency().toMillis())
                .append(" ms. Audit log: ").append(r.audit().size()).append(" events, hash chain ")
                .append(r.auditIntact() ? "verified intact" : "BROKEN").append(".\n\n")
                .append("| Stage | Status | Attempts | ms | Reason |\n|---|---|---|---|---|\n");
        for (StageResult s : r.report().stages()) {
            md.append("| ").append(s.stageId()).append(" | ").append(s.status()).append(" | ").append(s.attempts()).append(" | ")
                    .append(s.duration().toMillis()).append(" | ").append(s.reason().replace('|', '/').replace('\n', ' ')).append(" |\n");
        }
        md.append("\n## Audit trail\n\n```\n");
        for (AuditLog.Event e : r.audit()) {
            md.append(String.format("#%03d %-20s %-18s %s%n", e.seq(), e.type(), e.stageId() == null ? "-" : e.stageId(), e.detail()));
        }
        return md.append("```\n").toString();
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(".");
        Path out = null;
        List<Path> jacoco = new java.util.ArrayList<>();
        double target = 100;
        boolean interactive = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--jacoco" -> jacoco.add(Path.of(args[++i]));
                case "--target" -> target = Double.parseDouble(args[++i]);
                case "--interactive" -> interactive = true;
                default -> root = Path.of(args[i]);
            }
        }
        if (jacoco.isEmpty()) jacoco = DeliveryPipeline.jacocoReports(root);
        Result r = run(root, jacoco, target, CommandRunner.in(root),
                interactive ? new ConsoleApprovalGateway() : scriptedReleaseManager());

        System.out.println(runRecord(r));
        if (out != null) {
            Files.createDirectories(out);
            for (Map.Entry<String, String> a : r.artifacts().entrySet()) Files.writeString(out.resolve(a.getKey()), a.getValue());
            Files.writeString(out.resolve("pipeline-run.md"), runRecord(r));
            System.out.println("wrote " + (r.artifacts().size() + 1) + " files to " + out);
        }
    }
}
