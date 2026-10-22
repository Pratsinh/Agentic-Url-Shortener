package com.ascs.orchestrator.model;

import java.time.Duration;
import java.util.List;

/** Result of one run: outcome, per-stage results, latency and number of re-plans. */
public record RunReport(String runId, RunOutcome outcome, List<StageResult> stages, Duration latency, int replans) {

    public RunReport {
        stages = List.copyOf(stages);
    }

    public boolean success() {
        return outcome == RunOutcome.SUCCEEDED;
    }

    public StageResult result(String stageId) {
        return stages.stream().filter(s -> s.stageId().equals(stageId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown stage " + stageId));
    }

    public StageStatus status(String stageId) {
        return result(stageId).status();
    }
}
