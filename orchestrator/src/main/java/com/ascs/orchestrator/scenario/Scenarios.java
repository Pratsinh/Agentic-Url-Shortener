package com.ascs.orchestrator.scenario;

import com.ascs.orchestrator.approval.ApprovalGateway;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.Decision;
import com.ascs.orchestrator.model.RollbackAction;
import com.ascs.orchestrator.model.RunReport;
import com.ascs.orchestrator.planner.CodebaseImpactAnalyzer;
import com.ascs.orchestrator.planner.NormalizedRequirement;
import com.ascs.orchestrator.planner.RequirementAnalyzer;
import com.ascs.orchestrator.planner.RuleBasedRequirementAnalyzer;
import com.ascs.orchestrator.planner.WorkflowPlanner;
import com.ascs.orchestrator.policy.PolicyEngine;
import com.ascs.orchestrator.policy.ProtectedPathPolicy;
import com.ascs.orchestrator.policy.RequiredEvidencePolicy;
import com.ascs.orchestrator.policy.SecretLeakPolicy;
import com.ascs.orchestrator.sdlc.StubAgents;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The three required scenarios. Each goes: analyze -> decompose -> plan DAG -> govern & execute -> validate.
 * Agents are deterministic stubs with injected faults so every governance control fires reproducibly.
 */
public final class Scenarios {

    public record Result(String name, String requirement, NormalizedRequirement analysis, WorkflowGraph graph,
                         List<RunReport> runs, Metrics.Snapshot metrics, List<AuditLog.Event> audit,
                         boolean auditIntact, List<String> notes) {
    }

    private final RequirementAnalyzer analyzer = new RuleBasedRequirementAnalyzer();
    private final WorkflowPlanner planner = new WorkflowPlanner(Duration.ofMillis(20));
    private final CodebaseImpactAnalyzer codebase;
    private final ApprovalGateway approvalsOverride;

    /** The system the scenarios change: the shortener service module, analyzed as source (the orchestrator never links it). */
    public static final String TARGET_SYSTEM = "shortener-service";

    public Scenarios(Path projectRoot, ApprovalGateway approvalsOverride) {
        Path target = projectRoot.resolve(TARGET_SYSTEM);
        this.codebase = new CodebaseImpactAnalyzer(target.resolve("src/main/java"), target.resolve("src/test/java"));
        this.approvalsOverride = approvalsOverride;
    }

    // ------------------------------------------------------------------------------------------------ 1
    public Result greenfield() {
        String req = "Build a URL shortener from scratch with create, redirect, expiry and click analytics";
        NormalizedRequirement n = analyzer.analyze(req);
        WorkflowGraph graph = planner.plan(n.tasks(), Map.of());

        Map<String, Agent> agents = new HashMap<>(StubAgents.forTasks(n.tasks()));
        agents.put("requirements", ScenarioAgents.requirements(n));
        // transient fault: integration tests fail on attempt 1 (container start-up), pass on attempt 2
        agents.put("integration-tests", ScenarioAgents.failUntilAttempt(2, agents.get("integration-tests"),
                "Testcontainers start-up timeout (transient)"));
        // hard fault: the primary docs generator always crashes; the template fallback takes over
        agents.put("documentation", ScenarioAgents.alwaysFail("docs generator crashed: out of memory"));

        PolicyEngine policies = PolicyEngine.of(new SecretLeakPolicy(), new ProtectedPathPolicy(),
                new RequiredEvidencePolicy("release-readiness", "release-checklist.md",
                        "unit-test-report.md@", "integration-test-report.md@", "security-report.md@"));
        ApprovalGateway approvals = approvals(r -> Decision.approve("release-manager",
                "evidence reviewed: " + r.proposedOutputs().keySet()));

        AuditLog audit = new AuditLog(Clock.systemUTC());
        Metrics metrics = new Metrics();
        RunReport run = execute("greenfield-run-1", n, graph, agents, policies, approvals, new ArtifactStore(), audit, metrics, null);
        return result("Greenfield: build the URL shortener", req, n, graph, List.of(run), metrics, audit, List.of());
    }

    // ------------------------------------------------------------------------------------------------ 2
    public Result brownfield() {
        String req = "Replace the in-memory link repository with a Redis cache in front of Cassandra storage, keeping the public API unchanged";
        NormalizedRequirement n = analyzer.analyze(req);
        List<String> reverted = new CopyOnWriteArrayList<>();
        WorkflowGraph graph = planner.plan(n.tasks(), Map.<String, RollbackAction>of("implement-change",
                ctx -> reverted.add("git revert of " + ctx.artifacts() + " (run " + ctx.runId() + ")")));

        PolicyEngine policies = PolicyEngine.of(new SecretLeakPolicy(), new ProtectedPathPolicy(),
                new RequiredEvidencePolicy("release-readiness", "release-checklist.md",
                        "regression-report.md@", "security-report.md@"));
        ApprovalGateway approvals = approvals(r -> Decision.approve(r.stageId().equals("implement-change") ? "tech-lead" : "release-manager",
                "reviewed " + r.proposedOutputs().keySet() + " warnings=" + r.warnings()));

        AuditLog audit = new AuditLog(Clock.systemUTC());
        Metrics metrics = new Metrics();

        // Run 1: defective agents. The implementer leaks a credential on its first attempt (blocked by policy, retried),
        // then regression tests fail permanently -> the whole run is rolled back.
        Map<String, Agent> bad = new HashMap<>(StubAgents.forTasks(n.tasks()));
        bad.put("requirements", ScenarioAgents.requirements(n));
        bad.put("impact-analysis", ScenarioAgents.impactAnalysis(codebase, "LinkRepository", "InMemoryLinkRepository"));
        bad.put("implement-change", ScenarioAgents.leakSecretUntilAttempt(2, "change.patch", bad.get("implement-change")));
        bad.put("regression-tests", ScenarioAgents.alwaysFail("CassandraLinkRepositoryIT: node unreachable (connection refused)"));
        RunReport run1 = execute("brownfield-run-1", n, graph, bad, policies, approvals, new ArtifactStore(), audit, metrics, null);

        // Run 2: after a human fixes the defect, the same plan is re-executed from scratch.
        Map<String, Agent> good = new HashMap<>(StubAgents.forTasks(n.tasks()));
        good.put("requirements", ScenarioAgents.requirements(n));
        good.put("impact-analysis", ScenarioAgents.impactAnalysis(codebase, "LinkRepository", "InMemoryLinkRepository"));
        RunReport run2 = execute("brownfield-run-2", n, graph, good, policies, approvals, new ArtifactStore(), audit, metrics, null);

        List<String> notes = new java.util.ArrayList<>();
        reverted.forEach(r -> notes.add("compensation executed: " + r));
        return result("Brownfield: replace in-memory storage with Redis + Cassandra", req, n, graph, List.of(run1, run2),
                metrics, audit, notes);
    }

    // ------------------------------------------------------------------------------------------------ 3
    public Result ambiguous() {
        String req = "Make the links more secure and faster";
        NormalizedRequirement n = analyzer.analyze(req);
        WorkflowGraph graph = planner.plan(n.tasks(), Map.of());

        Map<String, Agent> agents = new HashMap<>(StubAgents.forTasks(n.tasks()));
        agents.put("requirements", ScenarioAgents.requirements(n));
        agents.put("clarification", ScenarioAgents.clarification(n));

        // Mid-run, the chief architect reviews design.md and tightens the latency target.
        AtomicReference<WorkflowEngine> engineRef = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean();
        Agent apiSpec = agents.get("api-spec");
        agents.put("api-spec", in -> {
            if (fired.compareAndSet(false, true)) {
                String design = in.artifact("design.md").map(Artifact::content).orElse("");
                engineRef.get().submitChange("design.md", design
                                + "\n## Review amendment (chief-architect)\n- p99 redirect latency target tightened from 50 ms to 20 ms\n- add read-through cache design\n",
                        "chief-architect", "latency target tightened after capacity review");
            }
            return apiSpec.execute(in);
        });

        PolicyEngine policies = PolicyEngine.of(new SecretLeakPolicy(), new ProtectedPathPolicy(),
                new RequiredEvidencePolicy("release-readiness", "release-checklist.md",
                        "unit-test-report.md@", "integration-test-report.md@", "security-report.md@"));
        ApprovalGateway approvals = approvals(r -> r.stageId().equals("clarification")
                ? Decision.approve("product-owner", "Confirmed: 'secure' = rate limiting + HTTPS-only option; 'faster' = p99 < 50 ms at 1k rps")
                : Decision.approve("release-manager", "evidence reviewed"));

        AuditLog audit = new AuditLog(Clock.systemUTC());
        Metrics metrics = new Metrics();
        RunReport run = execute("ambiguous-run-1", n, graph, agents, policies, approvals, new ArtifactStore(), audit, metrics, engineRef);
        return result("Ambiguous: \"make the links more secure and faster\"", req, n, graph, List.of(run), metrics, audit, List.of());
    }

    // ------------------------------------------------------------------------------------------------ plumbing
    private ApprovalGateway approvals(java.util.function.Function<com.ascs.orchestrator.model.ApprovalRequest, Decision> script) {
        return approvalsOverride != null ? approvalsOverride : new ScriptedApprovalGateway(script);
    }

    private RunReport execute(String runId, NormalizedRequirement n, WorkflowGraph graph, Map<String, Agent> agents,
                              PolicyEngine policies, ApprovalGateway approvals, ArtifactStore store, AuditLog audit,
                              Metrics metrics, AtomicReference<WorkflowEngine> engineRef) {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            audit.append(runId, null, AuditLog.Type.PLAN_CREATED, "kind=" + n.kind() + " tasks=" + n.tasks().size()
                    + " waves=" + graph.levels());
            WorkflowEngine engine = WorkflowEngine.builder(graph, agents, pool).store(store).audit(audit)
                    .metrics(metrics).policies(policies).approvals(approvals).build();
            if (engineRef != null) engineRef.set(engine);
            return engine.run(runId, n.original());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Result result(String name, String req, NormalizedRequirement n, WorkflowGraph g, List<RunReport> runs,
                                 Metrics metrics, AuditLog audit, List<String> notes) {
        return new Result(name, req, n, g, runs, metrics.snapshot(), audit.events(), audit.verify(), notes);
    }
}
