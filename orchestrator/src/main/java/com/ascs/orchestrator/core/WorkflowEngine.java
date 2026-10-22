package com.ascs.orchestrator.core;

import com.ascs.orchestrator.approval.ApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.audit.AuditLog.Type;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.*;
import com.ascs.orchestrator.policy.PolicyContext;
import com.ascs.orchestrator.policy.PolicyEngine;
import com.ascs.orchestrator.policy.Severity;
import com.ascs.orchestrator.policy.Violation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateful, governed DAG executor. One instance executes exactly one run.
 *
 * <p><b>Concurrency model.</b> A single coordinator thread (the caller of {@link #run}) owns ALL mutable run
 * state. Agents run on worker threads and see only an immutable {@link StageInput}. Everything asynchronous
 * (agent results, human decisions, change requests, kill switch) reaches the coordinator as an event on one
 * queue, so there are no locks around run state and no races between, say, a re-plan and a completing stage.
 * Stale results are detected with a per-stage generation counter and discarded.
 *
 * <p><b>Controls.</b> entry/exit gates, policy guardrails (BLOCK / CRITICAL), human approval before commit,
 * bounded retries with backoff, fallback agent, per-attempt timeout, failure budget, safe-stop, run rollback
 * with compensation, and change-driven re-planning bounded by a replan budget.
 */
public final class WorkflowEngine {

    // ------------------------------------------------------------------ wiring
    private final WorkflowGraph graph;
    private final Map<String, Agent> agents;
    private final ArtifactStore store;
    private final AuditLog audit;
    private final Clock clock;
    private final ExecutorService executor;
    private final PolicyEngine policies;
    private final ApprovalGateway approvals;
    private final Metrics metrics;
    private final RunOptions options;

    public static Builder builder(WorkflowGraph graph, Map<String, Agent> agents, ExecutorService executor) {
        return new Builder(graph, agents, executor);
    }

    public static final class Builder {
        private final WorkflowGraph graph;
        private final Map<String, Agent> agents;
        private final ExecutorService executor;
        private ArtifactStore store = new ArtifactStore();
        private Clock clock = Clock.systemUTC();
        private AuditLog audit;
        private PolicyEngine policies = PolicyEngine.none();
        private ApprovalGateway approvals;
        private Metrics metrics = new Metrics();
        private RunOptions options = RunOptions.defaults();

        private Builder(WorkflowGraph graph, Map<String, Agent> agents, ExecutorService executor) {
            this.graph = graph; this.agents = agents; this.executor = executor;
        }

        public Builder store(ArtifactStore v) { store = v; return this; }
        public Builder clock(Clock v) { clock = v; return this; }
        public Builder audit(AuditLog v) { audit = v; return this; }
        public Builder policies(PolicyEngine v) { policies = v; return this; }
        public Builder approvals(ApprovalGateway v) { approvals = v; return this; }
        public Builder metrics(Metrics v) { metrics = v; return this; }
        public Builder options(RunOptions v) { options = v; return this; }

        public WorkflowEngine build() {
            return new WorkflowEngine(graph, agents, store, audit != null ? audit : new AuditLog(clock),
                    clock, executor, policies, approvals, metrics, options);
        }
    }

    /** Minimal engine: no policies, no approvals (so no stage may require one). */
    public WorkflowEngine(WorkflowGraph graph, Map<String, Agent> agents, ArtifactStore store,
                          AuditLog audit, Clock clock, ExecutorService executor) {
        this(graph, agents, store, audit, clock, executor, PolicyEngine.none(), null, new Metrics(), RunOptions.defaults());
    }

    private WorkflowEngine(WorkflowGraph graph, Map<String, Agent> agents, ArtifactStore store, AuditLog audit,
                           Clock clock, ExecutorService executor, PolicyEngine policies,
                           ApprovalGateway approvals, Metrics metrics, RunOptions options) {
        this.graph = Objects.requireNonNull(graph);
        this.agents = Map.copyOf(agents);
        this.store = Objects.requireNonNull(store);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.policies = Objects.requireNonNull(policies);
        this.approvals = approvals;
        this.metrics = Objects.requireNonNull(metrics);
        this.options = Objects.requireNonNull(options);
        validateGovernance();
    }

    /** Governance by construction: a misconfigured workflow is refused before anything runs. */
    private void validateGovernance() {
        for (StageDefinition d : graph.stages()) {
            if (!agents.containsKey(d.agentName())) {
                throw new IllegalArgumentException("no agent registered for stage '" + d.id() + "' (agent '" + d.agentName() + "')");
            }
            if (d.fallbackAgent() != null && !agents.containsKey(d.fallbackAgent())) {
                throw new IllegalArgumentException("no fallback agent '" + d.fallbackAgent() + "' for stage '" + d.id() + "'");
            }
            if (d.impact() == Impact.HIGH && !d.requiresApproval()) {
                throw new IllegalArgumentException("change control: HIGH-impact stage '" + d.id() + "' must require human approval");
            }
            if (d.requiresApproval() && approvals == null) {
                throw new IllegalArgumentException("stage '" + d.id() + "' requires approval but no ApprovalGateway is configured");
            }
        }
    }

    public Metrics metrics() { return metrics; }

    // ------------------------------------------------------------------ thread-safe control surface
    /** Human change request: replaces the latest version of an artifact and re-plans the affected sub-graph. */
    public void submitChange(String artifactName, String newContent, String author, String reason) {
        inbox.offer(new ChangeSubmitted(artifactName, newContent, author, reason));
    }

    /** Kill switch: stop scheduling, cancel in-flight work, commit nothing further, keep state for inspection. */
    public void requestSafeStop(String reason) {
        inbox.offer(new SafeStopRequested(reason));
    }

    // ------------------------------------------------------------------ events & state
    private sealed interface Event permits AgentDone, ApprovalDecided, ChangeSubmitted, SafeStopRequested {}
    private record AgentDone(String stageId, int generation, AgentResult result, Throwable error) implements Event {}
    private record ApprovalDecided(String stageId, int generation, Decision decision) implements Event {}
    private record ChangeSubmitted(String name, String content, String author, String reason) implements Event {}
    private record SafeStopRequested(String reason) implements Event {}

    private final BlockingQueue<Event> inbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private enum HaltKind { SAFE_STOP, ROLLBACK }
    private record Halt(HaltKind kind, String reason) {}

    /** Mutable per-stage state; touched only by the coordinator thread. */
    private static final class Rec {
        StageStatus status = StageStatus.PENDING;
        String reason = "";
        int generation;
        int attempt;
        boolean inFallback;
        boolean usedFallback;
        Instant startedAt, finishedAt, firstFailureAt;
        long deadlineNanos;
        List<Artifact> upstream = List.of();
        List<ArtifactRef> inputRefs = List.of();
        List<ArtifactRef> committed = List.of();
        Map<String, String> pendingOutputs;
        String pendingRationale = "";
        Future<?> future;

        void reset() {
            status = StageStatus.PENDING; reason = ""; generation++; attempt = 0;
            inFallback = false; usedFallback = false; startedAt = null; finishedAt = null; firstFailureAt = null;
            deadlineNanos = 0; upstream = List.of(); inputRefs = List.of(); committed = List.of();
            pendingOutputs = null; pendingRationale = ""; future = null;
        }
    }

    private String runId;
    private String requirement;
    private final Map<String, Rec> recs = new LinkedHashMap<>();
    private Halt halt;
    private boolean aborted;
    private int replans;
    private int failures;

    // ------------------------------------------------------------------ run
    public RunReport run(String runId, String requirement) {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("a WorkflowEngine instance executes one run");
        this.runId = Objects.requireNonNull(runId);
        this.requirement = Objects.requireNonNull(requirement);
        Instant runStart = clock.instant();
        graph.topologicalOrder().forEach(id -> recs.put(id, new Rec()));
        log(null, Type.RUN_STARTED, "stages=" + recs.size());
        metrics.runStarted();

        try {
            loop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abortAll();
        }

        Instant end = clock.instant();
        RunOutcome outcome = outcome();
        log(null, Type.RUN_COMPLETED, "outcome=" + outcome + " replans=" + replans);
        metrics.runFinished(outcome, Duration.between(runStart, end));

        List<StageResult> results = new ArrayList<>();
        recs.forEach((id, r) -> results.add(new StageResult(id, r.status, r.reason,
                r.startedAt == null ? Duration.ZERO : Duration.between(r.startedAt, r.finishedAt == null ? end : r.finishedAt),
                r.attempt, r.usedFallback)));
        return new RunReport(runId, outcome, results, Duration.between(runStart, end), replans);
    }

    private RunOutcome outcome() {
        if (aborted) return RunOutcome.ABORTED;
        if (halt != null) {
            if (halt.kind() == HaltKind.SAFE_STOP) return RunOutcome.SAFE_STOPPED;
            boolean incomplete = recs.values().stream().anyMatch(r -> r.status == StageStatus.ROLLBACK_FAILED);
            return incomplete ? RunOutcome.ROLLBACK_INCOMPLETE : RunOutcome.ROLLED_BACK;
        }
        return recs.values().stream().allMatch(r -> r.status == StageStatus.SUCCEEDED) ? RunOutcome.SUCCEEDED : RunOutcome.FAILED;
    }

    private void loop() throws InterruptedException {
        while (true) {
            Event ev;
            while ((ev = inbox.poll()) != null) handle(ev);
            expireDeadlines();
            if (halt == null) schedule();
            if (inFlight() == 0) {
                if (inbox.isEmpty()) return;
                continue;
            }
            // something is RUNNING or AWAITING_APPROVAL, and both always carry a deadline
            long wait = nextDeadline() - System.nanoTime();
            ev = inbox.poll(Math.max(0L, wait), TimeUnit.NANOSECONDS); // a passed deadline polls without waiting
            if (ev != null) handle(ev);
        }
    }

    private int inFlight() {
        int n = 0;
        for (Rec r : recs.values()) if (r.status == StageStatus.RUNNING || r.status == StageStatus.AWAITING_APPROVAL) n++;
        return n;
    }

    private long nextDeadline() {
        long min = Long.MAX_VALUE;
        for (Rec r : recs.values()) {
            if ((r.status == StageStatus.RUNNING || r.status == StageStatus.AWAITING_APPROVAL)
                    && (min == Long.MAX_VALUE || r.deadlineNanos - min < 0)) {
                min = r.deadlineNanos;
            }
        }
        return min;
    }

    private void handle(Event ev) {
        switch (ev) {
            case AgentDone d -> onAgentDone(d);
            case ApprovalDecided a -> onApproval(a);
            case ChangeSubmitted c -> onChange(c);
            case SafeStopRequested s -> startHalt(HaltKind.SAFE_STOP, "kill switch: " + s.reason());
        }
    }

    // ------------------------------------------------------------------ scheduling
    private void schedule() {
        for (String id : graph.topologicalOrder()) {
            Rec rec = recs.get(id);
            if (rec.status != StageStatus.PENDING) continue;
            StageDefinition def = graph.stage(id);

            Optional<String> blocker = def.dependsOn().stream().sorted().filter(d -> {
                StageStatus s = recs.get(d).status;
                return s == StageStatus.FAILED || s == StageStatus.BLOCKED || s == StageStatus.REJECTED;
            }).findFirst();
            if (blocker.isPresent()) {
                block(id, rec, "upstream stage '" + blocker.get() + "' is " + recs.get(blocker.get()).status);
                continue;
            }
            if (!def.dependsOn().stream().allMatch(d -> recs.get(d).status == StageStatus.SUCCEEDED)) continue;

            Optional<String> denial = firstDenial(def.entryGates(), new GateContext(store, Map.of()));
            if (denial.isPresent()) {
                log(id, Type.ENTRY_GATE_DENIED, denial.get());
                block(id, rec, "entry gate denied: " + denial.get());
                continue;
            }

            rec.upstream = upstreamArtifacts(id);
            rec.inputRefs = rec.upstream.stream().map(Artifact::ref).toList();
            rec.status = StageStatus.RUNNING;
            rec.attempt = 1;
            rec.startedAt = clock.instant();
            log(id, Type.STAGE_STARTED, "agent=" + def.agentName() + " inputs=" + rec.inputRefs);
            submit(id, rec, def);
        }
    }

    private void submit(String id, Rec rec, StageDefinition def) {
        String agentName = rec.inFallback ? def.fallbackAgent() : def.agentName();
        Agent agent = agents.get(agentName);
        StageInput input = new StageInput(runId, id, requirement, rec.upstream, rec.attempt);
        int gen = rec.generation;
        long backoffMs = (rec.attempt > 1 && !rec.inFallback) ? def.retry().backoff().toMillis() * (rec.attempt - 1) : 0;
        rec.deadlineNanos = System.nanoTime() + options.stageTimeout().toNanos() + TimeUnit.MILLISECONDS.toNanos(backoffMs);
        try {
            rec.future = executor.submit(() -> {
                try {
                    if (backoffMs > 0) Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return; // cancelled while backing off: nothing to report
                }
                AgentResult result = null;
                Throwable error = null;
                try {
                    result = agent.execute(input);
                } catch (Throwable t) {
                    error = t;
                }
                inbox.offer(new AgentDone(id, gen, result, error));
            });
        } catch (RejectedExecutionException e) {
            markFailed(id, rec, "executor rejected task");
        }
    }

    // ------------------------------------------------------------------ agent results
    private void onAgentDone(AgentDone d) {
        Rec rec = recs.get(d.stageId());
        if (rec.status != StageStatus.RUNNING || rec.generation != d.generation()) {
            log(d.stageId(), Type.STAGE_RESULT_DISCARDED, "stale result (generation " + d.generation() + ")");
            return;
        }
        String id = d.stageId();
        StageDefinition def = graph.stage(id);
        rec.future = null;
        metrics.attempt();

        if (d.error() != null) { attemptFailed(id, rec, def, "agent error: " + d.error()); return; }
        AgentResult res = d.result();
        if (res == null || res.outputs() == null) { attemptFailed(id, rec, def, "agent returned no result"); return; }

        Optional<String> denial = firstDenial(def.exitGates(), new GateContext(store, res.outputs()));
        if (denial.isPresent()) {
            log(id, Type.EXIT_GATE_DENIED, denial.get());
            attemptFailed(id, rec, def, "exit gate denied: " + denial.get());
            return;
        }

        List<Violation> violations = policies.evaluate(new PolicyContext(def, res.outputs(), store));
        recordViolations(id, violations);
        if (PolicyEngine.has(violations, Severity.CRITICAL)) {
            String why = "critical policy violation: " + firstMessage(violations, Severity.CRITICAL);
            markFailed(id, rec, why);
            startHalt(HaltKind.SAFE_STOP, "stage '" + id + "': " + why);
            return;
        }
        if (PolicyEngine.has(violations, Severity.BLOCK)) {
            attemptFailed(id, rec, def, "policy violation: " + firstMessage(violations, Severity.BLOCK));
            return;
        }

        rec.pendingOutputs = new LinkedHashMap<>(res.outputs());
        rec.pendingRationale = String.valueOf(res.rationale());
        if (def.requiresApproval()) {
            List<String> warnings = violations.stream().filter(v -> v.severity() == Severity.WARN)
                    .map(v -> v.policyId() + ": " + v.message()).toList();
            requestApproval(id, rec, def, warnings);
        } else {
            commit(id, rec);
        }
    }

    private void attemptFailed(String id, Rec rec, StageDefinition def, String reason) {
        if (rec.firstFailureAt == null) rec.firstFailureAt = clock.instant();
        log(id, Type.STAGE_ATTEMPT_FAILED, "attempt=" + rec.attempt + (rec.inFallback ? " (fallback) " : " ") + reason);

        if (!rec.inFallback && rec.attempt < def.retry().maxAttempts()) {
            rec.attempt++;
            metrics.retry();
            log(id, Type.STAGE_RETRY, "attempt " + rec.attempt + "/" + def.retry().maxAttempts());
            submit(id, rec, def);
            return;
        }
        if (!rec.inFallback && def.fallbackAgent() != null) {
            rec.inFallback = true;
            rec.usedFallback = true;
            rec.attempt++;
            metrics.fallback();
            log(id, Type.FALLBACK_USED, "switching to fallback agent '" + def.fallbackAgent() + "'");
            submit(id, rec, def);
            return;
        }
        failStage(id, rec, def, reason);
    }

    /** Terminal failure + the stage's failure strategy. */
    private void failStage(String id, Rec rec, StageDefinition def, String reason) {
        markFailed(id, rec, reason);
        failures++;
        FailureStrategy strategy = def.onFailure();
        if (strategy == FailureStrategy.ROLLBACK_RUN) {
            startHalt(HaltKind.ROLLBACK, "stage '" + id + "' failed: " + reason);
        } else if (strategy == FailureStrategy.SAFE_STOP) {
            startHalt(HaltKind.SAFE_STOP, "stage '" + id + "' failed: " + reason);
        } else if (options.failureBudget() > 0 && failures >= options.failureBudget()) { // BLOCK_DESCENDANTS
            startHalt(HaltKind.SAFE_STOP, "failure budget exhausted (" + failures + " failed stages)");
        }
    }

    private void markFailed(String id, Rec rec, String reason) {
        rec.status = StageStatus.FAILED;
        rec.reason = reason;
        rec.finishedAt = clock.instant();
        rec.pendingOutputs = null;
        log(id, Type.STAGE_FAILED, reason);
        metrics.stageFailed();
    }

    private void block(String id, Rec rec, String reason) {
        rec.status = StageStatus.BLOCKED;
        rec.reason = reason;
        log(id, Type.STAGE_BLOCKED, reason);
    }

    private void commit(String id, Rec rec) {
        Instant now = clock.instant();
        List<ArtifactRef> committed = new ArrayList<>();
        rec.pendingOutputs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Artifact a = store.put(id, e.getKey(), e.getValue(), rec.inputRefs, now);
            committed.add(a.ref());
            log(id, Type.ARTIFACT_PRODUCED, a.ref() + " sha256=" + a.contentHash());
        });
        rec.committed = List.copyOf(committed);
        rec.pendingOutputs = null;
        rec.status = StageStatus.SUCCEEDED;
        rec.finishedAt = now;
        log(id, Type.STAGE_SUCCEEDED, rec.pendingRationale + (rec.inFallback ? " [fallback agent]" : ""));
        metrics.stageSucceeded();
        if (rec.firstFailureAt != null) {
            metrics.recovery(Duration.between(rec.firstFailureAt, now));
            rec.firstFailureAt = null;
        }
    }

    // ------------------------------------------------------------------ human approval
    private void requestApproval(String id, Rec rec, StageDefinition def, List<String> warnings) {
        rec.status = StageStatus.AWAITING_APPROVAL;
        rec.deadlineNanos = System.nanoTime() + options.approvalTimeout().toNanos();
        ApprovalRequest request = new ApprovalRequest(runId, id, def.impact(),
                def.description() + " | agent rationale: " + rec.pendingRationale, rec.pendingOutputs, warnings);
        log(id, Type.APPROVAL_REQUESTED, "impact=" + def.impact() + " outputs=" + new ArrayList<>(rec.pendingOutputs.keySet()));
        metrics.approvalRequested();
        int gen = rec.generation;
        try {
            approvals.request(request, decision -> inbox.offer(new ApprovalDecided(id, gen, decision)));
        } catch (RuntimeException e) {
            inbox.offer(new ApprovalDecided(id, gen, Decision.reject("system", "approval gateway failed: " + e)));
        }
    }

    private void onApproval(ApprovalDecided a) {
        Rec rec = recs.get(a.stageId());
        if (rec.status != StageStatus.AWAITING_APPROVAL || rec.generation != a.generation()) {
            log(a.stageId(), Type.STAGE_RESULT_DISCARDED, "stale approval decision");
            return;
        }
        Decision d = a.decision() == null ? Decision.reject("system", "null decision") : a.decision();
        if (d.approved()) {
            log(a.stageId(), Type.APPROVAL_GRANTED, "approver=" + d.approver() + " comment=" + d.comment());
            metrics.approvalGranted();
            commit(a.stageId(), rec);
        } else {
            rec.status = StageStatus.REJECTED;
            rec.reason = "rejected by " + d.approver() + ": " + d.comment();
            rec.finishedAt = clock.instant();
            rec.pendingOutputs = null;
            log(a.stageId(), Type.APPROVAL_REJECTED, "approver=" + d.approver() + " comment=" + d.comment());
            metrics.approvalRejected();
        }
    }

    // ------------------------------------------------------------------ timeouts
    private void expireDeadlines() {
        long now = System.nanoTime();
        for (String id : graph.topologicalOrder()) {
            Rec rec = recs.get(id);
            if (now - rec.deadlineNanos < 0) continue;
            if (rec.status == StageStatus.AWAITING_APPROVAL) {
                rec.generation++; // a late human answer is now stale
                rec.status = StageStatus.REJECTED;
                rec.reason = "approval timed out after " + options.approvalTimeout().toMillis() + " ms (fail closed)";
                rec.finishedAt = clock.instant();
                rec.pendingOutputs = null;
                log(id, Type.APPROVAL_TIMED_OUT, rec.reason);
                metrics.approvalTimedOut();
            } else if (rec.status == StageStatus.RUNNING) {
                cancel(rec);
                rec.generation++;
                metrics.attempt();
                attemptFailed(id, rec, graph.stage(id), "timed out after " + options.stageTimeout().toMillis() + " ms");
            }
        }
    }

    private void cancel(Rec rec) {
        if (rec.future != null) rec.future.cancel(true);
        rec.future = null;
    }

    // ------------------------------------------------------------------ safe-stop / rollback
    private void startHalt(HaltKind kind, String reason) {
        if (halt != null) return;
        halt = new Halt(kind, reason);
        log(null, Type.HALT_REQUESTED, kind + ": " + reason);
        for (String id : graph.topologicalOrder()) {
            Rec r = recs.get(id);
            switch (r.status) {
                case RUNNING -> { cancel(r); r.generation++; block(id, r, "halted (" + kind + "): in-flight work cancelled"); }
                case AWAITING_APPROVAL -> { r.generation++; r.pendingOutputs = null; block(id, r, "halted (" + kind + "): pending approval abandoned"); }
                case PENDING -> block(id, r, "run halted (" + kind + "): " + reason);
                default -> { }
            }
        }
        if (kind == HaltKind.ROLLBACK) {
            List<String> reverse = new ArrayList<>(graph.topologicalOrder());
            Collections.reverse(reverse); // undo dependents before their dependencies
            for (String id : reverse) {
                Rec r = recs.get(id);
                if (r.status != StageStatus.SUCCEEDED) continue;
                boolean ok = compensate(id, r, graph.stage(id));
                revokeOutputs(r, "run rolled back");
                r.status = ok ? StageStatus.ROLLED_BACK : StageStatus.ROLLBACK_FAILED;
                r.reason = ok ? "run rolled back" : "compensation failed: manual remediation required";
            }
        }
    }

    /** Runs the stage's compensating action (if any) with a timeout. Returns false if it failed. */
    private boolean compensate(String id, Rec rec, StageDefinition def) {
        if (def.rollback() == null) return true;
        log(id, Type.ROLLBACK_STARTED, "compensating " + rec.committed);
        Callable<Void> task = () -> {
            def.rollback().rollback(new RollbackAction.Context(runId, id, List.copyOf(rec.committed)));
            return null;
        };
        Future<Void> f = executor.submit(task);
        try {
            f.get(options.rollbackTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log(id, Type.ROLLBACK_COMPLETED, "ok");
            metrics.rollback();
            return true;
        } catch (TimeoutException e) {
            f.cancel(true);
            log(id, Type.ROLLBACK_FAILED, "timed out after " + options.rollbackTimeout().toMillis() + " ms");
        } catch (ExecutionException e) {
            log(id, Type.ROLLBACK_FAILED, String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(id, Type.ROLLBACK_FAILED, "interrupted");
        }
        metrics.rollbackFailed();
        return false;
    }

    private void revokeOutputs(Rec rec, String why) {
        rec.committed.forEach(ref -> store.revoke(ref, why));
    }

    private void abortAll() {
        aborted = true;
        recs.forEach((id, r) -> {
            if (r.status == StageStatus.RUNNING) { cancel(r); markFailed(id, r, "run interrupted"); }
            else if (r.status == StageStatus.PENDING || r.status == StageStatus.AWAITING_APPROVAL) block(id, r, "run aborted");
        });
        log(null, Type.RUN_ABORTED, "coordinator interrupted");
    }

    // ------------------------------------------------------------------ change requests & re-planning
    private void onChange(ChangeSubmitted c) {
        String who = c.author() + (c.reason() == null ? "" : " (" + c.reason() + ")");
        if (halt != null) { log(null, Type.CHANGE_REJECTED, c.name() + " by " + who + ": run is halted"); return; }
        Optional<Artifact> current = store.latest(c.name());
        if (current.isEmpty()) { log(null, Type.CHANGE_REJECTED, c.name() + " by " + who + ": no such artifact"); return; }
        Artifact old = current.get();

        if (replans >= options.maxReplans()) {
            log(null, Type.CHANGE_REJECTED, c.name() + " by " + who + ": replan budget (" + options.maxReplans() + ") exhausted");
            startHalt(HaltKind.SAFE_STOP, "replan budget exhausted: possible requirement thrash");
            return;
        }
        if (old.content().equals(c.content())) { log(null, Type.CHANGE_NOOP, c.name() + " unchanged"); return; }

        StageDefinition producer = graph.stage(old.producerStage());
        List<Violation> violations = policies.evaluate(new PolicyContext(producer, Map.of(c.name(), c.content()), store));
        recordViolations(old.producerStage(), violations);
        if (PolicyEngine.has(violations, Severity.BLOCK) || PolicyEngine.has(violations, Severity.CRITICAL)) {
            log(null, Type.CHANGE_REJECTED, c.name() + " by " + who + ": policy violation");
            return;
        }

        List<ArtifactRef> inputs = new ArrayList<>(old.inputs());
        inputs.add(old.ref());
        Artifact revised = store.put(old.producerStage(), c.name(), c.content(), inputs, clock.instant());
        Rec producerRec = recs.get(old.producerStage());
        List<ArtifactRef> committed = new ArrayList<>(producerRec.committed);
        committed.add(revised.ref());
        producerRec.committed = List.copyOf(committed);
        log(old.producerStage(), Type.CHANGE_APPLIED, old.ref() + " -> " + revised.ref() + " by " + who);
        replan(old.ref(), revised.ref());
    }

    /**
     * Minimal re-execution: only stages that actually consumed the superseded version (plus blocked ones) are
     * invalidated. Completed work is compensated, in-flight work is cancelled and its late result discarded.
     * Re-run stages pass every gate, policy and approval again.
     */
    private void replan(ArtifactRef oldRef, ArtifactRef newRef) {
        replans++;
        metrics.replan();
        List<String> affected = new ArrayList<>();
        for (String id : graph.topologicalOrder()) {
            Rec r = recs.get(id);
            if (r.status == StageStatus.PENDING) continue;
            if (r.status == StageStatus.BLOCKED || r.inputRefs.contains(oldRef)) affected.add(id);
        }
        log(null, Type.REPLAN, oldRef + " superseded by " + newRef + "; invalidating " + affected);

        List<String> reverse = new ArrayList<>(affected);
        Collections.reverse(reverse);
        for (String id : reverse) {
            Rec r = recs.get(id);
            switch (r.status) {
                case RUNNING -> cancel(r);
                case SUCCEEDED -> {
                    if (!compensate(id, r, graph.stage(id))) {
                        r.status = StageStatus.ROLLBACK_FAILED;
                        r.reason = "compensation failed during re-plan";
                        startHalt(HaltKind.SAFE_STOP, "compensation failed for '" + id + "' during re-plan");
                        return;
                    }
                    revokeOutputs(r, "invalidated by " + newRef);
                }
                default -> { }
            }
            log(id, Type.STAGE_INVALIDATED, "was " + r.status + "; will re-run");
            r.reset();
        }
    }

    // ------------------------------------------------------------------ helpers
    private void recordViolations(String stageId, List<Violation> violations) {
        for (Violation v : violations) {
            log(stageId, Type.POLICY_VIOLATION, v.category() + "/" + v.severity() + " " + v.policyId() + ": " + v.message());
            metrics.policyViolation(v.category(), v.severity());
        }
    }

    private static String firstMessage(List<Violation> vs, Severity s) {
        return vs.stream().filter(v -> v.severity() == s).map(Violation::message).findFirst().orElse("");
    }

    private void log(String stageId, Type type, String detail) {
        audit.append(runId, stageId, type, detail);
    }

    private Optional<String> firstDenial(List<Gate> gates, GateContext ctx) {
        for (Gate g : gates) {
            GateResult r;
            try {
                r = g.evaluate(ctx);
            } catch (RuntimeException ex) {
                r = GateResult.fail("gate threw " + ex); // a broken gate fails closed
            }
            if (!r.passed()) return Optional.of(g.name() + ": " + r.reason());
        }
        return Optional.empty();
    }

    /** Only artifacts produced by ancestor stages are visible: siblings in a parallel wave are isolated. */
    private List<Artifact> upstreamArtifacts(String stageId) {
        Set<String> ancestors = new LinkedHashSet<>(graph.ancestors(stageId));
        return store.latestAll().stream().filter(a -> ancestors.contains(a.producerStage())).toList();
    }
}
