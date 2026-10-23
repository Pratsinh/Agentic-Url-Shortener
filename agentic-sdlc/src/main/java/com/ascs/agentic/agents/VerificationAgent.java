package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.workspace.TestReport;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.StageInput;
import com.ascs.orchestrator.util.Hashing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic agent (no model) that runs the real build. Two modes:
 * <ul>
 *   <li>{@code baseline} (first delivery stage): proves the plan being executed is the approved version (artifact
 *       version and SHA-256 re-checked against the store), then runs the tests on the untouched base commit, so a
 *       later failure can be attributed to the change and a drop in test count detected.</li>
 *   <li>{@code verify} (after all tasks): runs the module's full test suite on the integrated change. It fails if
 *       any test fails, if the build does not pass, or if there are fewer tests than at baseline.</li>
 * </ul>
 */
public final class VerificationAgent implements Agent {

    public static final String BASELINE_JSON = "delivery-baseline.json", BASELINE_TESTS = "baseline-tests.json";
    public static final String VERIFY_JSON = "verification.json", PATCH = "change.patch";

    private final AgentKit kit;
    private final DeliveryContext ctx;
    private final ArtifactStore store;
    private final boolean baseline;

    public VerificationAgent(AgentKit kit, DeliveryContext ctx, ArtifactStore store, boolean baseline) {
        this.kit = kit;
        this.ctx = ctx;
        this.store = store;
        this.baseline = baseline;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        return baseline ? baseline(in) : verify(kit, in, "verify");
    }

    private AgentResult baseline(StageInput in) throws Exception {
        Map<String, Object> bridge = new LinkedHashMap<>();
        for (Artifact approved : List.of(ctx.analysisArtifact(), ctx.guidanceArtifact(), ctx.planArtifact())) {
            Artifact current = store.latest(approved.name()).orElseThrow(() -> new IllegalStateException(approved.name() + " missing"));
            if (!current.ref().equals(approved.ref()) || !Hashing.sha256(current.content()).equals(approved.contentHash())) {
                throw new IllegalStateException("integrity: " + approved.name() + " differs from the approved version " + approved.ref());
            }
            bridge.put(approved.name(), Map.of("ref", approved.ref().toString(), "sha256", approved.contentHash(),
                    "producer", approved.producerStage()));
        }
        bridge.put("planApprovals", kit.audit().events().stream()
                .filter(e -> e.type() == AuditLog.Type.APPROVAL_GRANTED && "plan".equals(e.stageId()))
                .map(e -> e.at() + " " + e.detail()).toList());
        bridge.put("workspace", Map.of("branch", kit.workspace().branch(), "baseCommit", kit.workspace().baseCommit()));
        TestReport t = kit.build().runTests(kit.workspace().root(), kit.config().verifyModule(), null);
        if (!t.passed()) throw new IllegalStateException("baseline build fails before any change: " + t.summary());
        return new AgentResult(Map.of(BASELINE_JSON, Json.pretty(bridge), BASELINE_TESTS, Json.pretty(t)),
                "approved plan " + ctx.planArtifact().ref() + " verified by hash; baseline " + t.summary());
    }

    /** Full verification of the current workspace; shared with the repair and review-fix agents. */
    static AgentResult verify(AgentKit kit, StageInput in, String who) throws Exception {
        TestReport base = AgentKit.upstream(in, BASELINE_TESTS, TestReport.class);
        TestReport t = kit.build().runTests(kit.workspace().root(), kit.config().verifyModule(), null);
        if (!t.passed()) {
            throw new IllegalStateException("verification failed: " + t.summary() + " " + t.failedTests());
        }
        if (t.tests() < base.tests()) {
            throw new IllegalStateException("test count dropped from " + base.tests() + " to " + t.tests() + ": tests were removed");
        }
        String diff = kit.workspace().diff();
        if (diff.isBlank()) throw new IllegalStateException("verification found no change in the workspace");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("verifiedBy", who);
        report.put("tests", t);
        report.put("baseline", Map.of("tests", base.tests(), "lineCoverage", base.lineCoverage() == null ? "n/a" : base.lineCoverage()));
        report.put("testsAdded", t.tests() - base.tests());
        report.put("changedFiles", kit.workspace().changedFiles());
        report.put("diffStat", kit.workspace().diffStat());
        return new AgentResult(Map.of(VERIFY_JSON, Json.pretty(report), PATCH, diff),
                who + ": " + t.summary() + " (baseline " + base.tests() + " tests, +" + (t.tests() - base.tests()) + ")");
    }
}
