package com.ascs.orchestrator;

import com.ascs.Verifies;
import com.ascs.orchestrator.agents.CommandRunner;
import com.ascs.orchestrator.agents.DeliveryPipeline;
import com.ascs.orchestrator.agents.DeliveryRunner;
import com.ascs.orchestrator.agents.ReleaseReadinessAgent;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.model.ApprovalRequest;
import com.ascs.orchestrator.model.Decision;
import com.ascs.orchestrator.model.Impact;
import com.ascs.orchestrator.model.RunOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The whole agentic delivery pipeline, run against this repository. */
class DeliveryPipelineTest {

    private static final CommandRunner FAKE_GIT = cmd -> cmd.get(1).equals("log")
            ? List.of("a1b2c3d\tfeat(agents): add the delivery pipeline") : List.of();

    @Test
    @Verifies({"AC-7.1", "AC-7.3", "AC-8.1", "AC-8.2", "AC-8.3", "AC-8.4", "AC-8.5"})
    void everyRoleAgentRunsUnderGovernanceAndTheReleaseIsApprovedByAHuman(@TempDir Path tmp) {
        DeliveryRunner.Result r = DeliveryRunner.run(RepoRoot.PATH, List.of(tmp.resolve("no-jacoco.xml")), 100, FAKE_GIT,
                DeliveryRunner.scriptedReleaseManager());

        assertEquals(RunOutcome.SUCCEEDED, r.report().outcome());
        assertEquals(List.of("code-review.md", "design.md", "development-report.md", "qa-report.md",
                "release-readiness.md", "user-stories.md"), List.copyOf(r.artifacts().keySet()));
        assertTrue(r.auditIntact());
        assertTrue(r.audit().stream().anyMatch(e -> e.type() == AuditLog.Type.APPROVAL_GRANTED
                && e.detail().startsWith("approver=release-manager")));
        String release = r.artifacts().get(ReleaseReadinessAgent.OUTPUT);
        assertTrue(release.contains("code-review.md@1") && release.contains("qa-report.md@1"), "evidence is cited");
        assertTrue(release.contains("READY WITH DOCUMENTED GAPS"), "missing coverage data is a documented gap");
        assertEquals(6, DeliveryPipeline.graph().stages().size());

        String record = DeliveryRunner.runRecord(r);
        assertTrue(record.contains("| release-readiness | SUCCEEDED | 1 |"));
        assertTrue(record.contains("hash chain verified intact"));
        assertTrue(DeliveryRunner.runRecord(new DeliveryRunner.Result(r.report(), r.artifacts(), r.audit(), false))
                .contains("hash chain BROKEN"));
    }

    @Test
    void releaseManagerRejectsWhenTheAgentRecommendsAgainstReleasing() {
        AtomicReference<Decision> decision = new AtomicReference<>();
        DeliveryRunner.scriptedReleaseManager().request(new ApprovalRequest("r", "release-readiness", Impact.HIGH, "s",
                Map.of(ReleaseReadinessAgent.OUTPUT, "**Recommendation:** NOT READY"), List.of()), decision::set);
        assertFalse(decision.get().approved());
    }

    @Test
    void mainWritesEveryArtifactAndTheRunRecord(@TempDir Path out) throws Exception {
        DeliveryRunner.main(new String[]{RepoRoot.PATH.toString(), "--out", out.toString(), "--jacoco", out.resolve("none.xml").toString(), "--target", "90"});
        for (String f : List.of("user-stories.md", "design.md", "development-report.md", "code-review.md", "qa-report.md",
                "release-readiness.md", "pipeline-run.md")) {
            assertTrue(Files.size(out.resolve(f)) > 0, f);
        }
        assertTrue(Files.readString(out.resolve("qa-report.md")).contains("Target: **90%**"));
    }

    @Test
    void interactiveModeAsksAHumanOnTheTerminal() throws Exception {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream("n not today\n".getBytes(StandardCharsets.UTF_8)));
        try {
            DeliveryRunner.main(new String[]{"--interactive"}); // no --out: prints only
        } finally {
            System.setIn(original);
        }
    }
}
