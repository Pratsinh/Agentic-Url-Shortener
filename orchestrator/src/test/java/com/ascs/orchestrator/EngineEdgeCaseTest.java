package com.ascs.orchestrator;

import com.ascs.orchestrator.approval.ApprovalGateway;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.*;
import com.ascs.orchestrator.policy.Policy;
import com.ascs.orchestrator.policy.PolicyCategory;
import com.ascs.orchestrator.policy.PolicyContext;
import com.ascs.orchestrator.policy.PolicyEngine;
import com.ascs.orchestrator.policy.Severity;
import com.ascs.orchestrator.policy.Violation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Engine paths that only occur under faults: interrupts, rejected work, misbehaving gateways, late events. */
class EngineEdgeCaseTest {

    private ExecutorService pool;
    private ArtifactStore store;
    private AuditLog audit;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
        store = new ArtifactStore();
        audit = new AuditLog(Clock.systemUTC());
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private WorkflowEngine.Builder eng(WorkflowGraph g, Map<String, Agent> agents) {
        return Fx.engine(g, agents, pool).store(store).audit(audit);
    }

    private long count(AuditLog.Type t) { return Fx.count(audit, t); }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition not reached in time");
            Thread.sleep(5);
        }
    }

    private static Agent blocking(CountDownLatch started) {
        return in -> { started.countDown(); Thread.sleep(60_000); return null; };
    }

    /** Runs {@code r} on its own thread so the test can interrupt the coordinator. */
    private static Thread coordinator(Runnable r) {
        Thread t = new Thread(r, "coordinator-under-test");
        t.start();
        return t;
    }

    @Test
    void builderAcceptsAClockAndExposesItsMetrics() {
        Metrics m = new Metrics();
        WorkflowEngine e = eng(Fx.graph(Fx.stage("a").build()), Map.of("a", Fx.ok("a"))).clock(Clock.systemUTC()).metrics(m).build();
        assertSame(m, e.metrics());
        assertTrue(e.run("r", "q").success());
    }

    @Test
    void anAgentThatReturnsNothingFailsItsAttempts() {
        var g = Fx.graph(Fx.stage("a").retry(RetryPolicy.of(2, Duration.ZERO)).build());
        Agent agent = in -> in.attempt() == 1 ? null : new AgentResult(null, "empty");
        RunReport r = eng(g, Map.of("a", agent)).build().run("r", "q");
        assertEquals(StageStatus.FAILED, r.status("a"));
        assertEquals("agent returned no result", r.result("a").reason());
        assertEquals(2, r.result("a").attempts());
    }

    @Test
    void rejectedWorkFailsTheStageAndALateResultIsDiscarded() {
        // runs the task inline, then claims saturation: the stage fails while its result is already queued
        ExecutorService runsThenRejects = new AbstractExecutorService() {
            @Override public void execute(Runnable task) { task.run(); throw new RejectedExecutionException("saturated"); }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
        };
        RunReport r = Fx.engine(Fx.graph(Fx.stage("a").build()), Map.of("a", Fx.ok("a")), runsThenRejects)
                .audit(audit).build().run("r", "q");
        assertEquals(StageStatus.FAILED, r.status("a"));
        assertEquals("executor rejected task", r.result("a").reason());
        assertEquals(1, count(AuditLog.Type.STAGE_RESULT_DISCARDED));
    }

    @Test
    void safeStopFailureStrategyFreezesTheRun() {
        var g = Fx.graph(Fx.stage("a").onFailure(FailureStrategy.SAFE_STOP).build(), Fx.stage("b", "a").build());
        RunReport r = eng(g, Map.of("a", Fx.boom("x"), "b", Fx.ok("b"))).build().run("r", "q");
        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(StageStatus.BLOCKED, r.status("b"));
    }

    @Test
    void aSecondKillSwitchIsIdempotent() {
        WorkflowEngine e = eng(Fx.graph(Fx.stage("a").build()), Map.of("a", Fx.ok("a"))).build();
        e.requestSafeStop("first");
        e.requestSafeStop("second");
        e.submitChange("a.txt", "late", "intern", "too late");
        RunReport r = e.run("r", "q");
        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(1, count(AuditLog.Type.HALT_REQUESTED));
        assertEquals(StageStatus.BLOCKED, r.status("a"));
        assertTrue(audit.events().stream().anyMatch(ev -> ev.detail().endsWith("by intern (too late): run is halted")));
    }

    @Test
    void interruptingTheCoordinatorAbortsRunningPendingAndAwaitingStages() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ScriptedApprovalGateway silent = ScriptedApprovalGateway.silent();
        var g = Fx.graph(Fx.stage("a").build(), Fx.stage("b", "a").build(), Fx.stage("c").requireApproval().build(),
                Fx.stage("done").build());
        Metrics metrics = new Metrics();
        WorkflowEngine e = eng(g, Map.of("a", blocking(started), "b", Fx.ok("b"), "c", Fx.ok("c"), "done", Fx.ok("done")))
                .approvals(silent).metrics(metrics).build();

        AtomicReference<RunReport> report = new AtomicReference<>();
        Thread t = coordinator(() -> report.set(e.run("r", "q")));
        started.await();
        await(() -> silent.requests().size() == 1);
        await(() -> store.latest("done.txt").isPresent());
        t.interrupt();
        t.join(10_000);

        RunReport r = report.get();
        assertEquals(RunOutcome.ABORTED, r.outcome());
        assertEquals(StageStatus.FAILED, r.status("a"));
        assertEquals("run interrupted", r.result("a").reason());
        assertEquals(StageStatus.BLOCKED, r.status("b"));
        assertEquals(StageStatus.BLOCKED, r.status("c"));
        assertEquals(StageStatus.SUCCEEDED, r.status("done"), "finished work is left as it is");
        assertEquals(1, count(AuditLog.Type.RUN_ABORTED));
        assertEquals(1, metrics.snapshot().runsCompleted());
    }

    @Test
    void interruptDuringCompensationIsReportedAsAFailedRollback() throws Exception {
        CountDownLatch compensating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var g = Fx.graph(
                Fx.stage("a").rollback(ctx -> { compensating.countDown(); release.await(); }).build(),
                Fx.stage("b", "a").onFailure(FailureStrategy.ROLLBACK_RUN).build());
        WorkflowEngine e = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.boom("deploy failed"))).build();

        AtomicReference<RunReport> report = new AtomicReference<>();
        Thread t = coordinator(() -> report.set(e.run("r", "q")));
        compensating.await();
        t.interrupt();
        t.join(10_000);
        release.countDown();

        assertEquals(RunOutcome.ROLLBACK_INCOMPLETE, report.get().outcome());
        assertTrue(audit.events().stream().anyMatch(ev -> ev.type() == AuditLog.Type.ROLLBACK_FAILED && ev.detail().equals("interrupted")));
    }

    @Test
    void compensationThatOverrunsItsTimeoutIsReportedAsFailed() {
        var g = Fx.graph(
                Fx.stage("a").rollback(ctx -> Thread.sleep(5_000)).build(),
                Fx.stage("b", "a").onFailure(FailureStrategy.ROLLBACK_RUN).build());
        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.boom("x")))
                .options(RunOptions.defaults().withRollbackTimeout(Duration.ofMillis(50))).build().run("r", "q");
        assertEquals(RunOutcome.ROLLBACK_INCOMPLETE, r.outcome());
        assertTrue(audit.events().stream().anyMatch(ev -> ev.type() == AuditLog.Type.ROLLBACK_FAILED && ev.detail().startsWith("timed out")));
    }

    @Test
    void aHaltCancelsARetryThatIsStillBackingOff() {
        AtomicReference<WorkflowEngine> ref = new AtomicReference<>();
        Agent flaky = in -> { if (in.attempt() == 1) throw new IllegalStateException("transient"); return Fx.ok("a").execute(in); };
        Agent stopper = in -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (count(AuditLog.Type.STAGE_RETRY) == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            Thread.sleep(200); // let the retry reach its (one-minute) backoff sleep before halting
            ref.get().requestSafeStop("operator");
            return Fx.ok("b").execute(in);
        };
        var g = Fx.graph(Fx.stage("a").retry(RetryPolicy.of(2, Duration.ofMinutes(1))).build(), Fx.stage("b").build());
        WorkflowEngine e = eng(g, Map.of("a", flaky, "b", stopper)).build();
        ref.set(e);

        RunReport r = e.run("r", "q");
        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(StageStatus.BLOCKED, r.status("a"));
        assertTrue(r.result("a").reason().contains("in-flight work cancelled"));
    }

    @Test
    void misbehavingApprovalGatewaysFailClosed() {
        var g = Fx.graph(Fx.stage("a").requireApproval().build(), Fx.stage("b").requireApproval().build(),
                Fx.stage("c").requireApproval().build());
        ApprovalGateway gw = (req, callback) -> {
            switch (req.stageId()) {
                case "a" -> throw new IllegalStateException("gateway down");
                case "b" -> callback.accept(null);
                default -> { callback.accept(Decision.approve("lead", "ok")); callback.accept(Decision.reject("lead", "changed my mind")); }
            }
        };
        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.ok("b"), "c", Fx.ok("c"))).approvals(gw).build().run("r", "q");

        assertEquals(StageStatus.REJECTED, r.status("a"));
        assertTrue(r.result("a").reason().contains("approval gateway failed"));
        assertEquals(StageStatus.REJECTED, r.status("b"));
        assertTrue(r.result("b").reason().contains("null decision"));
        assertEquals(StageStatus.SUCCEEDED, r.status("c"), "a second, late answer cannot undo the first");
        assertEquals(1, count(AuditLog.Type.STAGE_RESULT_DISCARDED));
    }

    @Test
    void haltAbandonsAPendingApproval() throws Exception {
        ScriptedApprovalGateway silent = ScriptedApprovalGateway.silent();
        AtomicReference<WorkflowEngine> ref = new AtomicReference<>();
        Agent stopper = in -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (silent.requests().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            ref.get().requestSafeStop("operator");
            return Fx.ok("b").execute(in);
        };
        var g = Fx.graph(Fx.stage("a").requireApproval().build(), Fx.stage("b").build());
        WorkflowEngine e = eng(g, Map.of("a", Fx.ok("a"), "b", stopper)).approvals(silent).build();
        ref.set(e);

        RunReport r = e.run("r", "q");
        assertEquals(StageStatus.BLOCKED, r.status("a"));
        assertTrue(r.result("a").reason().contains("pending approval abandoned"));
        assertTrue(store.latest("a.txt").isEmpty(), "an unapproved output is never committed");
    }

    /** CRITICAL whenever any output contains the word "forbidden". */
    private static final Policy FORBIDDEN = new Policy() {
        @Override public String id() { return "test.forbidden"; }
        @Override public PolicyCategory category() { return PolicyCategory.SECURITY; }
        @Override public List<Violation> evaluate(PolicyContext ctx) {
            return ctx.outputs().values().stream().anyMatch(v -> v.contains("forbidden"))
                    ? List.of(violation(Severity.CRITICAL, "forbidden content")) : List.of();
        }
    };

    @Test
    void aChangeWithACriticalViolationIsRejected() {
        AtomicReference<WorkflowEngine> ref = new AtomicReference<>();
        Agent b = in -> { ref.get().submitChange("a.txt", "forbidden", "intern", "oops"); return Fx.ok("b").execute(in); };
        WorkflowEngine e = eng(Fx.graph(Fx.stage("a").build(), Fx.stage("b", "a").build()), Map.of("a", Fx.ok("a"), "b", b))
                .policies(PolicyEngine.of(FORBIDDEN)).build();
        ref.set(e);

        RunReport r = e.run("r", "q");
        assertTrue(r.success());
        assertEquals(1, count(AuditLog.Type.CHANGE_REJECTED));
        assertEquals(0, r.replans());
    }

    @Test
    void replanCancelsRunningWorkAndRetriesBlockedStages() {
        AtomicReference<WorkflowEngine> ref = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();
        Agent e1 = in -> {
            if (once.compareAndSet(false, true)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (count(AuditLog.Type.STAGE_BLOCKED) == 0 && System.nanoTime() < deadline) Thread.sleep(5);
                ref.get().submitChange("a.txt", "a v2", "architect", "amended");
            }
            return Fx.ok("e").execute(in);
        };
        var g = Fx.graph(Fx.stage("a").build(), Fx.stage("x").build(), Fx.stage("d", "x").build(), Fx.stage("e", "a").build());
        WorkflowEngine e = eng(g, new HashMap<>(Map.of("a", Fx.ok("a"), "x", Fx.boom("x down"), "d", Fx.ok("d"), "e", e1))).build();
        ref.set(e);

        RunReport r = e.run("r", "q");
        assertEquals(1, r.replans());
        assertEquals(StageStatus.SUCCEEDED, r.status("e"));
        assertEquals(StageStatus.BLOCKED, r.status("d"));
        assertEquals("a.txt@2", store.latest("a.txt").orElseThrow().ref().toString());
        assertTrue(audit.events().stream().anyMatch(ev -> ev.type() == AuditLog.Type.STAGE_INVALIDATED && "d".equals(ev.stageId())));
    }

    @Test
    void aTimedOutAttemptsLateResultIsDiscardedWhileItsRetryRuns() {
        CountDownLatch retryStarted = new CountDownLatch(1);
        Agent slow = in -> {
            if (in.attempt() == 1) {
                // ignores the cancellation interrupt and answers late, after the retry has started
                while (retryStarted.getCount() > 0) Thread.onSpinWait();
                return Fx.ok("a").execute(in);
            }
            retryStarted.countDown();
            Thread.sleep(50); // well inside the 200 ms timeout; the stale answer arrives meanwhile
            return Fx.ok("a").execute(in);
        };
        var g = Fx.graph(Fx.stage("a").retry(RetryPolicy.of(2, Duration.ZERO)).build());
        RunReport r = eng(g, Map.of("a", slow)).options(RunOptions.defaults().withStageTimeout(Duration.ofMillis(200)))
                .build().run("r", "q");

        assertEquals(StageStatus.SUCCEEDED, r.status("a"));
        assertEquals(2, r.result("a").attempts());
        assertTrue(audit.events().stream().anyMatch(e -> e.type() == AuditLog.Type.STAGE_RESULT_DISCARDED
                && e.detail().startsWith("stale result")));
    }

    /** Emits the configured severities for any stage whose id is listed. */
    private static Policy emitting(Map<String, List<Severity>> byStage) {
        return new Policy() {
            @Override public String id() { return "test.emitting"; }
            @Override public PolicyCategory category() { return PolicyCategory.COMPLIANCE; }
            @Override public List<Violation> evaluate(PolicyContext ctx) {
                return byStage.getOrDefault(ctx.stage().id(), List.of()).stream()
                        .map(s -> violation(s, s + " finding")).toList();
            }
        };
    }

    @Test
    void onlyWarningsReachTheReviewerAndTheBlockingMessageIsReported() {
        ScriptedApprovalGateway gw = ScriptedApprovalGateway.approveAll("lead", "ok");
        var g = Fx.graph(Fx.stage("a").requireApproval().build(), Fx.stage("b").build());
        RunReport r = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.ok("b"))).approvals(gw)
                .policies(PolicyEngine.of(emitting(Map.of("a", List.of(Severity.INFO, Severity.WARN),
                        "b", List.of(Severity.WARN, Severity.BLOCK))))).build().run("r", "q");

        assertEquals(List.of("test.emitting: WARN finding"), gw.requests().get(0).warnings());
        assertEquals("policy violation: BLOCK finding", r.result("b").reason());
    }

    @Test
    void failureBudgetIsNotTrippedBeforeItIsReached() {
        var g = Fx.graph(Fx.stage("a").build(), Fx.stage("b").build());
        RunReport r = eng(g, Map.of("a", Fx.boom("x"), "b", Fx.ok("b")))
                .options(RunOptions.defaults().withFailureBudget(2)).build().run("r", "q");
        assertEquals(RunOutcome.FAILED, r.outcome());
        assertEquals(0, count(AuditLog.Type.HALT_REQUESTED));
    }

    @Test
    void everyKindOfUnhealthyDependencyBlocksItsDependents() {
        ScriptedApprovalGateway no = new ScriptedApprovalGateway(req -> Decision.reject("lead", "no"));
        var g = Fx.graph(Fx.stage("rejected").requireApproval().build(), Fx.stage("afterRejected", "rejected").build(),
                Fx.stage("failed").build(), Fx.stage("afterFailed", "failed").build(), Fx.stage("afterBlocked", "afterFailed").build());
        RunReport r = eng(g, Map.of("rejected", Fx.ok("rejected"), "afterRejected", Fx.ok("afterRejected"),
                "failed", Fx.boom("x"), "afterFailed", Fx.ok("afterFailed"), "afterBlocked", Fx.ok("afterBlocked")))
                .approvals(no).build().run("r", "q");

        assertEquals("upstream stage 'rejected' is REJECTED", r.result("afterRejected").reason());
        assertEquals("upstream stage 'failed' is FAILED", r.result("afterFailed").reason());
        assertEquals("upstream stage 'afterFailed' is BLOCKED", r.result("afterBlocked").reason());
    }

    @Test
    void aDecisionForAnApprovalThatWasReplannedIsDiscarded() {
        List<java.util.function.Consumer<Decision>> callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
        ApprovalGateway parked = (req, callback) -> callbacks.add(callback);
        AtomicReference<WorkflowEngine> ref = new AtomicReference<>();
        Agent amender = in -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (callbacks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            ref.get().submitChange("a.txt", "a v2", "architect", null);
            while (callbacks.size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            callbacks.get(0).accept(Decision.approve("lead", "approving what I saw first"));
            callbacks.get(1).accept(Decision.approve("lead", "approving the re-run"));
            return Fx.ok("x").execute(in);
        };
        var g = Fx.graph(Fx.stage("a").build(), Fx.stage("b", "a").requireApproval().build(), Fx.stage("x").build());
        WorkflowEngine e = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.ok("b"), "x", amender)).approvals(parked).build();
        ref.set(e);

        RunReport r = e.run("r", "q");
        assertEquals(StageStatus.SUCCEEDED, r.status("b"));
        assertTrue(audit.events().stream().anyMatch(ev -> ev.detail().equals("stale approval decision")));
        assertTrue(audit.events().stream().anyMatch(ev -> ev.type() == AuditLog.Type.CHANGE_APPLIED && ev.detail().endsWith("by architect")));
        assertEquals("[a.txt@2]", store.latest("b.txt").orElseThrow().inputs().toString());
    }

    @Test
    void failedCompensationDuringReplanSafeStops() {
        AtomicReference<WorkflowEngine> ref = new AtomicReference<>();
        Agent c = in -> { ref.get().submitChange("a.txt", "a v2", "architect", "amended"); return Fx.ok("c").execute(in); };
        var g = Fx.graph(Fx.stage("a").build(),
                Fx.stage("b", "a").rollback(ctx -> { throw new IllegalStateException("cannot revert"); }).build(),
                Fx.stage("c", "b").build());
        WorkflowEngine e = eng(g, Map.of("a", Fx.ok("a"), "b", Fx.ok("b"), "c", c)).build();
        ref.set(e);

        RunReport r = e.run("r", "q");
        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(StageStatus.ROLLBACK_FAILED, r.status("b"));
        assertEquals("compensation failed during re-plan", r.result("b").reason());
    }
}
