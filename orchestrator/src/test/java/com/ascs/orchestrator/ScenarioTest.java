package com.ascs.orchestrator;

import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.model.RunOutcome;
import com.ascs.orchestrator.model.StageStatus;
import com.ascs.orchestrator.scenario.Scenarios;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The three assignment scenarios, asserted end to end. */
class ScenarioTest {

    private final Scenarios scenarios = new Scenarios(RepoRoot.PATH, null);

    private static long count(Scenarios.Result r, AuditLog.Type t) {
        return r.audit().stream().filter(e -> e.type() == t).count();
    }

    @Test
    void greenfieldSucceedsUsingRetryFallbackAndAHumanApproval() {
        Scenarios.Result r = scenarios.greenfield();
        var run = r.runs().get(0);

        assertEquals(RunOutcome.SUCCEEDED, run.outcome());
        assertEquals(2, run.result("integration-tests").attempts());
        assertTrue(run.result("documentation").usedFallback());
        assertEquals(1, r.metrics().fallbacks());
        assertEquals(2, r.metrics().retries());
        assertEquals(1, r.metrics().approvalsGranted());
        assertTrue(r.metrics().mttrMs() > 0);
        assertTrue(r.auditIntact());
    }

    @Test
    void brownfieldContainsABadChangeThenSucceedsAfterTheFix() {
        Scenarios.Result r = scenarios.brownfield();

        assertEquals(RunOutcome.ROLLED_BACK, r.runs().get(0).outcome());
        assertEquals(StageStatus.ROLLED_BACK, r.runs().get(0).status("implement-change"));
        assertEquals(StageStatus.FAILED, r.runs().get(0).status("regression-tests"));
        assertEquals(RunOutcome.SUCCEEDED, r.runs().get(1).outcome());

        assertEquals(1, r.metrics().policyViolations().get("SECURITY/BLOCK"));
        assertEquals(1, r.metrics().rollbacks());
        assertEquals(0.5, r.metrics().runSuccessRate());
        assertEquals(0.5, r.metrics().rollbackRate());
        assertTrue(r.notes().get(0).contains("compensation executed"));
        assertTrue(r.auditIntact());
        // the leaked credential never reached the audit log
        // review:accepted CR-01 asserts the example key id never reaches the audit log
        r.audit().forEach(e -> assertFalse(e.detail().contains("AKIAIOSFODNN7EXAMPLE")));
    }

    @Test
    void ambiguousRequirementIsClarifiedThenReplannedMinimally() {
        Scenarios.Result r = scenarios.ambiguous();
        var run = r.runs().get(0);

        assertEquals(RunOutcome.SUCCEEDED, run.outcome());
        assertEquals(2, r.analysis().ambiguities().size());
        assertEquals(1, run.replans());
        assertEquals(2, r.metrics().approvalsRequested(), "clarification once + release once");
        assertEquals(1, count(r, AuditLog.Type.CHANGE_APPLIED));
        Set<String> untouched = Set.of("requirements", "clarification", "design");
        r.audit().stream().filter(e -> e.type() == AuditLog.Type.STAGE_INVALIDATED)
                .forEach(e -> assertFalse(untouched.contains(e.stageId()), "re-plan must be minimal, but invalidated " + e.stageId()));
        assertTrue(r.auditIntact());
    }
}
