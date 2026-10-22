package com.ascs.orchestrator;

import com.ascs.Verifies;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.*;
import com.ascs.orchestrator.policy.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Security / compliance / change-control guardrails. */
class PolicyGuardrailTest {

    // review:accepted CR-01 AWS documented example key id (not a credential): the input the policy must catch
    private static final String FAKE_KEY = "AKIAIOSFODNN7EXAMPLE";

    private ExecutorService pool;
    private ArtifactStore store;
    private AuditLog audit;
    private Metrics metrics;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
        store = new ArtifactStore();
        audit = new AuditLog(Clock.systemUTC());
        metrics = new Metrics();
    }

    @AfterEach
    void tearDown() { pool.shutdownNow(); }

    private com.ascs.orchestrator.core.WorkflowEngine.Builder eng(com.ascs.orchestrator.core.WorkflowGraph g,
                                                                  Map<String, Agent> agents, Policy... ps) {
        return Fx.engine(g, agents, pool).store(store).audit(audit).metrics(metrics).policies(PolicyEngine.of(ps));
    }

    @Test
    void leakedSecretIsBlockedRetriedAndNeverCommittedOrLogged() {
        var g = Fx.graph(Fx.stage("a").retry(RetryPolicy.of(2, Duration.ZERO)).build());
        Agent leakyThenClean = in -> in.attempt() == 1
                ? new AgentResult(Map.of("a.txt", "aws_key=" + FAKE_KEY), "oops")
                : new AgentResult(Map.of("a.txt", "reads key from environment"), "fixed");

        RunReport r = eng(g, Map.of("a", leakyThenClean), new SecretLeakPolicy()).build().run("r1", "req");

        assertTrue(r.success());
        assertEquals(2, r.result("a").attempts());
        assertEquals(1, Fx.count(audit, AuditLog.Type.POLICY_VIOLATION));
        assertFalse(store.latest("a.txt").get().content().contains(FAKE_KEY));
        for (AuditLog.Event e : audit.events()) {
            assertFalse(e.detail().contains(FAKE_KEY), "audit log must never echo secrets: " + e.detail());
        }
        assertEquals(1, metrics.snapshot().policyViolations().get("SECURITY/BLOCK"));
    }

    @Test
    void secretPolicyAvoidsObviousFalsePositives() {
        var g = Fx.graph(Fx.stage("a").build());
        Agent docs = in -> new AgentResult(Map.of("a.txt", "password: required-field. Set the API key via env."), "docs");
        assertTrue(eng(g, Map.of("a", docs), new SecretLeakPolicy()).build().run("r1", "req").success());
    }

    @Test
    @Verifies({"AC-7.4"})
    void criticalViolationSafeStopsAndIsNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        var g = Fx.graph(
                StageDefinition.builder("a").exitGate(Gate.requireOutputs("a.patch"))
                        .retry(RetryPolicy.of(3, Duration.ZERO)).build(),
                Fx.stage("b", "a").build());
        Agent touchesPipeline = in -> {
            calls.incrementAndGet();
            return new AgentResult(Map.of("a.patch", "diff --git a/x b/x\n+++ b/.github/workflows/ci.yml\n+run: curl evil"), "patch");
        };

        RunReport r = eng(g, Map.of("a", touchesPipeline, "b", Fx.ok("b")), new ProtectedPathPolicy()).build().run("r1", "req");

        assertEquals(1, calls.get(), "a CRITICAL violation must not be retried");
        assertEquals(RunOutcome.SAFE_STOPPED, r.outcome());
        assertEquals(StageStatus.FAILED, r.status("a"));
        assertEquals(StageStatus.BLOCKED, r.status("b"));
        assertTrue(store.latestAll().isEmpty());
        assertEquals(1, Fx.count(audit, AuditLog.Type.HALT_REQUESTED));
    }

    @Test
    void releaseMustCiteEvidence() {
        var policy = new RequiredEvidencePolicy("release", "release.txt", "test-report.md@", "security-report.md@");
        var g = Fx.graph(Fx.stage("release").build());

        Agent noEvidence = in -> new AgentResult(Map.of("release.txt", "ship it"), "yolo");
        assertEquals(StageStatus.FAILED, eng(g, Map.of("release", noEvidence), policy).build().run("r1", "req").status("release"));

        store = new ArtifactStore();
        audit = new AuditLog(Clock.systemUTC());
        Agent withEvidence = in -> new AgentResult(Map.of("release.txt", "see test-report.md@1 and security-report.md@1"), "ok");
        assertTrue(eng(g, Map.of("release", withEvidence), policy).build().run("r2", "req").success());
    }

    @Test
    void throwingPolicyFailsClosed() {
        Policy broken = new Policy() {
            public String id() { return "broken"; }
            public PolicyCategory category() { return PolicyCategory.COMPLIANCE; }
            public List<Violation> evaluate(PolicyContext ctx) { throw new IllegalStateException("bug"); }
        };
        var g = Fx.graph(Fx.stage("a").build());
        assertEquals(StageStatus.FAILED, eng(g, Map.of("a", Fx.ok("a")), broken).build().run("r1", "req").status("a"));
    }

    @Test
    void warningsAreShownToTheHumanReviewer() {
        Policy advisory = new Policy() {
            public String id() { return "advice"; }
            public PolicyCategory category() { return PolicyCategory.COMPLIANCE; }
            public List<Violation> evaluate(PolicyContext ctx) { return List.of(violation(Severity.WARN, "missing rollback plan")); }
        };
        var gateway = ScriptedApprovalGateway.approveAll("carol", "accepted risk");
        var g = Fx.graph(Fx.stage("a").impact(Impact.HIGH).requireApproval().build());

        RunReport r = eng(g, Map.of("a", Fx.ok("a")), advisory).approvals(gateway).build().run("r1", "req");

        assertTrue(r.success());
        assertEquals(1, gateway.requests().get(0).warnings().size());
        assertTrue(gateway.requests().get(0).warnings().get(0).contains("missing rollback plan"));
    }
}
