package com.ascs.agentic.pipeline;

import com.ascs.agentic.agents.AgentKit;
import com.ascs.agentic.agents.ClarificationAgent;
import com.ascs.agentic.agents.CommitAgent;
import com.ascs.agentic.agents.DeliveryContext;
import com.ascs.agentic.agents.DocsAgent;
import com.ascs.agentic.agents.ImplementerAgent;
import com.ascs.agentic.agents.PlannerAgent;
import com.ascs.agentic.agents.ReleaseReadinessAgent;
import com.ascs.agentic.agents.RepairAgent;
import com.ascs.agentic.agents.RequirementAnalystAgent;
import com.ascs.agentic.agents.ReviewFixAgent;
import com.ascs.agentic.agents.ReviewerAgent;
import com.ascs.agentic.agents.SecurityScanAgent;
import com.ascs.agentic.agents.VerificationAgent;
import com.ascs.agentic.config.AgenticConfig;
import com.ascs.agentic.human.HumanApprovalGateway;
import com.ascs.agentic.human.HumanChannel;
import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.Llm;
import com.ascs.agentic.llm.LlmLedger;
import com.ascs.agentic.llm.RecordingProvider;
import com.ascs.agentic.llm.Redactor;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.agentic.tools.WorkspaceTools;
import com.ascs.agentic.tools.WorkspaceWritePolicy;
import com.ascs.agentic.tools.WriteScope;
import com.ascs.agentic.workspace.BuildRunner;
import com.ascs.agentic.workspace.GitWorkspace;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.RunOptions;
import com.ascs.orchestrator.model.RunOutcome;
import com.ascs.orchestrator.model.RunReport;
import com.ascs.orchestrator.model.StageStatus;
import com.ascs.orchestrator.policy.PolicyEngine;
import com.ascs.orchestrator.policy.ProtectedPathPolicy;
import com.ascs.orchestrator.policy.SecretLeakPolicy;
import com.ascs.orchestrator.tools.Tool;
import com.ascs.orchestrator.tools.ToolGateway;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Runs one requirement end to end: an isolated workspace, the discovery workflow (analysis, clarification, plan
 * with human approval), then the delivery workflow compiled from the approved plan (implementation, verification,
 * review, docs, security, human release approval, commit). Both phases share one audit log, artifact store,
 * metrics and model ledger, all persisted under {@code .agentic/runs/<runId>}.
 */
public final class AgenticPipeline {

    /** What a run produced (also written to {@code state.json}). */
    public record Result(String runId, String status, RunReport discovery, RunReport delivery, Path runDir,
                         String branch, String commit) {}

    private final AgenticConfig config;
    private final Llm.Provider provider;
    private final BuildRunner build;
    private final Function<Path, HumanChannel> humanFactory;
    private final Clock clock;
    private final Redactor redactor;

    /** @param humanFactory creates the human channel for a run, given the run's inbox directory */
    public AgenticPipeline(AgenticConfig config, Llm.Provider provider, BuildRunner build,
                           Function<Path, HumanChannel> humanFactory, Clock clock, Redactor redactor) {
        this.config = config;
        this.provider = provider;
        this.build = build;
        this.humanFactory = humanFactory;
        this.clock = clock;
        this.redactor = redactor;
    }

    public Result run(String runId, String requirement, String label) throws Exception {
        Instant start = clock.instant();
        RunRecorder rec = new RunRecorder(config.runsDir().resolve(runId), redactor);
        AuditLog audit = new AuditLog(clock, rec::onAudit);
        ArtifactStore store = new ArtifactStore();
        rec.attach(store);
        Metrics metrics = new Metrics();
        LlmLedger ledger = new LlmLedger(config.maxRunCostUsd());
        Llm.Provider llm = new RecordingProvider(provider, ledger, audit, clock, redactor, rec::transcript);
        rec.write("requirement.md", "# Requirement (" + label + ")\n\n" + requirement + "\n");
        audit.append(runId, null, AuditLog.Type.PHASE_STARTED, "setup label=" + label + " provider=" + provider.name()
                + " model=" + config.model() + " reviewModel=" + config.reviewModel() + " effort=" + config.effort());

        GitWorkspace ws = GitWorkspace.create(config.repoRoot(), config.worktreesDir(), runId);
        audit.append(runId, null, AuditLog.Type.WORKSPACE_CHANGED, "worktree created branch=" + ws.branch() + " base=" + ws.baseCommit());
        HumanChannel human = humanFactory.apply(rec.dir().resolve("inbox"));
        Map<String, WriteScope> scopes = new ConcurrentHashMap<>();
        Map<String, Boolean> baseFiles = new ConcurrentHashMap<>();
        WorkspaceWritePolicy writePolicy = new WorkspaceWritePolicy(scopes::get, p -> baseFiles.computeIfAbsent(p, k -> existed(ws, k)));
        List<Tool> tools = new WorkspaceTools(ws, build, Set.of(config.verifyModule())).all();
        AtomicReference<WorkflowEngine> current = new AtomicReference<>();
        HumanApprovalGateway approvals = new HumanApprovalGateway(human, audit, (stage, who, feedback) -> {
            if (!stage.equals("plan") || current.get() == null) return false;
            String guidance = store.latest(ClarificationAgent.GUIDANCE).map(a -> a.content()).orElse("");
            current.get().submitChange(ClarificationAgent.GUIDANCE, guidance + "\n\n## Plan revision requested by " + who
                    + " (binding)\n" + feedback + "\n", who, "plan revision requested");
            return true;
        });
        PolicyEngine policies = PolicyEngine.of(new SecretLeakPolicy(), new ProtectedPathPolicy());
        RunOptions options = RunOptions.defaults().withMaxReplans(3).withStageTimeout(config.stageTimeout())
                .withApprovalTimeout(config.approvalTimeout()).withRollbackTimeout(Duration.ofMinutes(2));
        ExecutorService pool = Executors.newFixedThreadPool(config.maxParallel());
        RunReport discovery = null, delivery = null;
        String status, commit = null;
        try {
            // ---------------------------------------------------------------- discovery
            ToolGateway gw1 = gateway(audit, approvals, tools, writePolicy, Map.of(
                    "analyze", List.of(WorkspaceTools.LIST, WorkspaceTools.READ, WorkspaceTools.SEARCH),
                    "plan", List.of(WorkspaceTools.LIST, WorkspaceTools.READ, WorkspaceTools.SEARCH)));
            AgentKit kit1 = new AgentKit(config, new AgentLoop(llm, gw1, config.maxLlmTurns(), 3), ws, build, audit, human, ledger);
            Map<String, Agent> a1 = Map.of("analyze", new RequirementAnalystAgent(kit1), "clarify", new ClarificationAgent(kit1),
                    "plan", new PlannerAgent(kit1));
            audit.append(runId, null, AuditLog.Type.PHASE_STARTED, "discovery stages=" + Graphs.DISCOVERY);
            WorkflowEngine e1 = engine(Graphs.discovery(), a1, store, audit, metrics, approvals, policies, options, pool);
            current.set(e1);
            discovery = e1.run(runId, requirement);
            audit.append(runId, null, AuditLog.Type.PHASE_COMPLETED, "discovery outcome=" + discovery.outcome() + " replans=" + discovery.replans());
            if (!discovery.success()) {
                status = "STOPPED_IN_DISCOVERY_" + discovery.outcome();
                ws.discard();
                audit.append(runId, null, AuditLog.Type.WORKSPACE_CHANGED, "worktree and branch discarded (nothing was implemented)");
                return finish(rec, audit, store, metrics, ledger, runId, label, requirement, status, discovery, null, ws, null, start);
            }

            // ---------------------------------------------------------------- delivery (compiled from the approved plan)
            DeliveryContext ctx = DeliveryContext.fromStore(requirement, store);
            Map<String, List<String>> grants = new LinkedHashMap<>();
            List<String> all = new ArrayList<>(WorkspaceTools.READ_TOOLS);
            all.addAll(WorkspaceTools.WRITE_TOOLS);
            for (Models.PlanTask t : ctx.plan().tasks()) {
                scopes.put(ImplementerAgent.stageId(t.id()), ctx.scopeOf(t));
                grants.put(ImplementerAgent.stageId(t.id()), all);
            }
            scopes.put(RepairAgent.TOOL_STAGE, ctx.unionScope());
            scopes.put(ReviewFixAgent.TOOL_STAGE, ctx.unionScope());
            scopes.put("docs", new WriteScope(Set.of(DocsAgent.docPath(runId)), Set.of(), List.of()));
            grants.put(RepairAgent.TOOL_STAGE, all);
            grants.put(ReviewFixAgent.TOOL_STAGE, all);
            grants.put("review", WorkspaceTools.READ_TOOLS);
            grants.put("docs", List.of(WorkspaceTools.LIST, WorkspaceTools.READ, WorkspaceTools.SEARCH, WorkspaceTools.WRITE));
            ToolGateway gw2 = gateway(audit, approvals, tools, writePolicy, grants);
            AgentKit kit2 = new AgentKit(config, new AgentLoop(llm, gw2, config.maxLlmTurns(), 3), ws, build, audit, human, ledger);
            Map<String, Agent> a2 = new HashMap<>();
            a2.put("baseline", new VerificationAgent(kit2, ctx, store, true));
            ctx.plan().tasks().forEach(t -> a2.put(ImplementerAgent.stageId(t.id()), new ImplementerAgent(kit2, ctx, t.id())));
            a2.put("verify", new VerificationAgent(kit2, ctx, store, false));
            a2.put("repair", new RepairAgent(kit2, ctx));
            ReviewerAgent reviewer = new ReviewerAgent(kit2, ctx);
            a2.put("review", reviewer);
            a2.put("review-fix", new ReviewFixAgent(kit2, ctx, reviewer));
            a2.put("docs", new DocsAgent(kit2, ctx));
            a2.put("security", new SecurityScanAgent(kit2));
            a2.put("release", new ReleaseReadinessAgent(kit2, ctx));
            a2.put("commit", new CommitAgent(kit2, ctx));
            WorkflowGraph g2 = Graphs.delivery(ctx.plan(), rb -> ws.resetToBase());
            audit.append(runId, null, AuditLog.Type.PHASE_STARTED, "delivery compiled from " + ctx.planArtifact().ref()
                    + " waves=" + g2.levels());
            WorkflowEngine e2 = engine(g2, a2, store, audit, metrics, approvals, policies, options, pool);
            current.set(e2);
            delivery = e2.run(runId, requirement);
            audit.append(runId, null, AuditLog.Type.PHASE_COMPLETED, "delivery outcome=" + delivery.outcome() + " replans=" + delivery.replans());

            if (delivery.success()) {
                commit = Json.parse(store.latest(CommitAgent.JSON).orElseThrow().content()).path("commit").asText();
                ws.removeWorktree();
                audit.append(runId, null, AuditLog.Type.WORKSPACE_CHANGED, "worktree removed; approved commit " + commit + " kept on " + ws.branch());
                status = "DELIVERED";
            } else if (delivery.status("release") == StageStatus.REJECTED) {
                audit.append(runId, "release", AuditLog.Type.ROLLBACK_STARTED, "release rejected by a human: discarding the change");
                metrics.rollback();
                ws.discard();
                audit.append(runId, "release", AuditLog.Type.ROLLBACK_COMPLETED, "worktree and branch " + ws.branch() + " discarded");
                status = "REJECTED_AT_RELEASE";
            } else if (delivery.outcome() == RunOutcome.ROLLED_BACK) {
                ws.discard();
                audit.append(runId, null, AuditLog.Type.WORKSPACE_CHANGED, "rolled back: worktree and branch discarded");
                status = "ROLLED_BACK";
            } else {
                status = "STOPPED_IN_DELIVERY_" + delivery.outcome();
                audit.append(runId, null, AuditLog.Type.WORKSPACE_CHANGED, "worktree kept for inspection at " + ws.root());
            }
            return finish(rec, audit, store, metrics, ledger, runId, label, requirement, status, discovery, delivery, ws, commit, start);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean existed(GitWorkspace ws, String path) {
        try {
            return ws.existedAtBase(path);
        } catch (Exception e) {
            return true; // fail closed: an unknown file is treated as existing code, which needs explicit scope
        }
    }

    private ToolGateway gateway(AuditLog audit, HumanApprovalGateway approvals, List<Tool> tools, WorkspaceWritePolicy policy,
                                Map<String, List<String>> grants) {
        ToolGateway.Builder b = ToolGateway.builder(audit, approvals).approvalTimeout(config.approvalTimeout())
                .maxCallsPerRun(2_000).maxResultChars(60_000).readOnlyAttempts(2).policy(policy);
        tools.forEach(b::tool);
        grants.forEach((stage, names) -> b.grant(stage, names.toArray(String[]::new)));
        return b.build();
    }

    private WorkflowEngine engine(WorkflowGraph g, Map<String, Agent> agents, ArtifactStore store, AuditLog audit,
                                         Metrics metrics, HumanApprovalGateway approvals, PolicyEngine policies,
                                         RunOptions options, ExecutorService pool) {
        return WorkflowEngine.builder(g, agents, pool).store(store).audit(audit).metrics(metrics).approvals(approvals)
                .policies(policies).options(options).clock(clock).build();
    }

    private Result finish(RunRecorder rec, AuditLog audit, ArtifactStore store, Metrics metrics, LlmLedger ledger, String runId,
                          String label, String requirement, String status, RunReport discovery, RunReport delivery,
                          GitWorkspace ws, String commit, Instant start) {
        Duration latency = Duration.between(start, clock.instant());
        audit.append(runId, null, AuditLog.Type.RUN_COMPLETED, "pipeline status=" + status + " latencyMs=" + latency.toMillis());
        Result result = new Result(runId, status, discovery, delivery, rec.dir(), ws.branch(), commit);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("runId", runId);
        state.put("label", label);
        state.put("status", status);
        state.put("latencyMs", latency.toMillis());
        state.put("discovery", discovery);
        state.put("delivery", delivery);
        state.put("workspace", Map.of("branch", ws.branch(), "baseCommit", ws.baseCommit(), "path", ws.root().toString()));
        state.put("commit", commit == null ? "" : commit);
        state.put("provider", provider.name());
        rec.write("state.json", Json.pretty(state));
        rec.write("metrics.json", Json.pretty(Map.of("engine", metrics.snapshot(), "llm", ledger.snapshot(),
                "endToEndLatencyMs", latency.toMillis())));
        StringBuilder calls = new StringBuilder();
        ledger.calls().forEach(c -> calls.append(Json.compact(c)).append('\n'));
        rec.write("llm/calls.jsonl", calls.toString());
        rec.write("summary.md", RunReportWriter.render(result, label, requirement, store, audit, metrics.snapshot(), ledger,
                latency, rec.errors()));
        return result;
    }
}
