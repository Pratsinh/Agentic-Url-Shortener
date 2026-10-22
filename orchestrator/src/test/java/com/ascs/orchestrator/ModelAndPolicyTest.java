package com.ascs.orchestrator;

import com.ascs.Verifies;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.*;
import com.ascs.orchestrator.policy.PolicyCategory;
import com.ascs.orchestrator.policy.PolicyContext;
import com.ascs.orchestrator.policy.ProtectedPathPolicy;
import com.ascs.orchestrator.policy.RequiredEvidencePolicy;
import com.ascs.orchestrator.policy.Severity;
import com.ascs.orchestrator.sdlc.SdlcWorkflow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Validation and edge cases of the value types, store, audit chain, policies and metrics. */
class ModelAndPolicyTest {

    @Test
    void runOptionsAndRetryPolicyRejectUnsafeLimits() {
        RunOptions d = RunOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> d.withMaxReplans(-1));
        assertThrows(IllegalArgumentException.class, () -> d.withFailureBudget(-1));
        assertThrows(IllegalArgumentException.class, () -> d.withStageTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> d.withStageTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> d.withApprovalTimeout(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> d.withRollbackTimeout(Duration.ofDays(2)));
        assertEquals(Duration.ofSeconds(5), d.withRollbackTimeout(Duration.ofSeconds(5)).rollbackTimeout());

        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(11, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> RetryPolicy.of(2, null));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(2, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(2, Duration.ofMinutes(6)));
    }

    @Test
    void lookupsOfUnknownThingsFailOrReturnEmpty() {
        RunReport report = new RunReport("r", RunOutcome.SUCCEEDED, List.of(), Duration.ZERO, 0);
        assertThrows(IllegalArgumentException.class, () -> report.result("nope"));
        assertThrows(IllegalArgumentException.class, () -> SdlcWorkflow.standardGraph().stage("nope"));
        assertEquals("custom-agent", StageDefinition.builder("s").agent("custom-agent").build().agentName());

        ArtifactStore store = new ArtifactStore();
        Artifact a = store.put("s", "x.md", "v1", List.of(new ArtifactRef("ghost.md", 1)), Instant.EPOCH);
        store.revoke(new ArtifactRef("x.md", 9), "no such version");
        assertFalse(store.isRevoked(new ArtifactRef("x.md", 9)));
        assertTrue(store.get(new ArtifactRef("x.md", 0)).isEmpty());
        assertTrue(store.get(new ArtifactRef("x.md", 2)).isEmpty());
        assertEquals(List.of(a), store.lineage(a.ref()), "a dangling input is skipped, not fatal");
        assertTrue(store.lineage(new ArtifactRef("missing.md", 1)).isEmpty());
        store.revoke(a.ref(), "rolled back");
        assertTrue(store.latest("x.md").isEmpty());
    }

    @Test
    void requireOutputsRejectsBlankAndMissingOutputs() {
        Gate gate = Gate.requireOutputs("x.md");
        assertFalse(gate.evaluate(new GateContext(new ArtifactStore(), Map.of("x.md", "  "))).passed());
        assertFalse(gate.evaluate(new GateContext(new ArtifactStore(), Map.of())).passed());
        assertTrue(gate.evaluate(new GateContext(new ArtifactStore(), Map.of("x.md", "ok"))).passed());
    }

    @Test
    @Verifies({"AC-7.6"})
    void auditVerificationDetectsReorderingRelinkingAndEditing() {
        AuditLog log = new AuditLog(Clock.systemUTC());
        log.append("r", null, AuditLog.Type.RUN_STARTED, "a");
        log.append("r", "s", AuditLog.Type.STAGE_STARTED, "b");
        List<AuditLog.Event> ok = log.events();
        assertTrue(AuditLog.verifyChain(ok));

        AuditLog.Event first = ok.get(0), second = ok.get(1);
        assertFalse(AuditLog.verifyChain(List.of(second, first)), "reordered");
        AuditLog.Event relinked = new AuditLog.Event(1, second.at(), "r", "s", second.type(), "b", AuditLog.GENESIS, second.hash());
        assertFalse(AuditLog.verifyChain(List.of(first, relinked)), "wrong predecessor");
        AuditLog.Event edited = new AuditLog.Event(1, second.at(), "r", "s", second.type(), "B", second.prevHash(), second.hash());
        assertFalse(AuditLog.verifyChain(List.of(first, edited)), "edited detail");
    }

    @Test
    void evidenceAndProtectedPathPoliciesIgnoreWhatTheyDoNotGovern() {
        StageDefinition release = StageDefinition.builder("release").build();
        RequiredEvidencePolicy evidence = new RequiredEvidencePolicy("release", "checklist.md", "tests@");
        assertTrue(evidence.evaluate(new PolicyContext(release, Map.of("other.md", "x"), new ArtifactStore())).isEmpty());
        assertEquals(PolicyCategory.COMPLIANCE, evidence.category());

        ProtectedPathPolicy paths = new ProtectedPathPolicy(List.of(".github/workflows"));
        StageDefinition impl = StageDefinition.builder("impl").build();
        assertTrue(paths.evaluate(new PolicyContext(impl, Map.of("notes.md", "+++ .github/workflows/ci.yml"), new ArtifactStore())).isEmpty());
        assertTrue(paths.evaluate(new PolicyContext(impl, Map.of("a.patch", "--- a/src/App.java\n+++ b/src/App.java\n+ ok"),
                new ArtifactStore())).isEmpty());
        assertEquals(Severity.CRITICAL, paths.evaluate(new PolicyContext(impl, Map.of("b.patch",
                "diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml"), new ArtifactStore())).get(0).severity());
        assertEquals(1, paths.evaluate(new PolicyContext(impl, Map.of("c.patch",
                "--- a/.github/workflows/ci.yml\n+++ b/.github/workflows/ci.yml"), new ArtifactStore())).size(), "one violation per patch");
    }

    @Test
    void metricsCountEveryOutcomeAndFormatViolations() {
        Metrics m = new Metrics();
        for (RunOutcome o : RunOutcome.values()) { m.runStarted(); m.runFinished(o, Duration.ofMillis(10)); }
        m.policyViolation(PolicyCategory.SECURITY, Severity.BLOCK);
        Metrics.Snapshot s = m.snapshot();
        assertEquals(RunOutcome.values().length, s.runsCompleted());
        assertEquals(1, s.runsRollbackIncomplete());
        assertEquals(1, s.runsSafeStopped());
        assertTrue(s.format().contains("policy violations: {SECURITY/BLOCK=1}"));
    }
}
