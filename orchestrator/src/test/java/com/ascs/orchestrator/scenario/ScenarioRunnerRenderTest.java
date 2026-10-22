package com.ascs.orchestrator.scenario;

import com.ascs.orchestrator.model.RunReport;
import com.ascs.orchestrator.model.StageResult;
import com.ascs.orchestrator.model.StageStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report must expose a broken audit chain and tolerate stages without a reason. */
class ScenarioRunnerRenderTest {

    @Test
    void brokenAuditChainsAndMissingReasonsAreRenderedSafely() {
        Scenarios.Result real = new Scenarios(com.ascs.orchestrator.RepoRoot.PATH, null).ambiguous();
        RunReport run = new RunReport("x", real.runs().get(0).outcome(),
                List.of(new StageResult("s", StageStatus.BLOCKED, null, Duration.ZERO, 0, false)), Duration.ZERO, 0);
        Scenarios.Result tampered = new Scenarios.Result(real.name(), real.requirement(), real.analysis(), real.graph(),
                List.of(run), real.metrics(), real.audit(), false, real.notes());

        String md = ScenarioRunner.render(1, tampered);
        assertTrue(md.contains("hash chain **BROKEN**"));
        assertTrue(md.contains("| s | BLOCKED | 0 |  | 0 |  |"));
    }
}
