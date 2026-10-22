package com.ascs.orchestrator.model;

import java.time.Duration;

/** Final state of one stage in a run, including attempts and whether the fallback agent was used. */
public record StageResult(String stageId, StageStatus status, String reason, Duration duration,
                          int attempts, boolean usedFallback) {
}
