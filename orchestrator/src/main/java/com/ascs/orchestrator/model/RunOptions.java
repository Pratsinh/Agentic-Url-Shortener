package com.ascs.orchestrator.model;

import java.time.Duration;

/**
 * Run-level safety limits.
 * @param maxReplans      change-driven re-plans allowed before the run safe-stops (prevents thrash loops)
 * @param failureBudget   permanently failed stages tolerated before safe-stop (0 = unlimited)
 * @param approvalTimeout how long a human has to decide; expiry = rejection (fail closed)
 * @param stageTimeout    max wall time for one agent attempt; expiry counts as a failed attempt
 * @param rollbackTimeout max wall time for one compensating action
 */
public record RunOptions(int maxReplans, int failureBudget, Duration approvalTimeout,
                         Duration stageTimeout, Duration rollbackTimeout) {

    public RunOptions {
        if (maxReplans < 0 || failureBudget < 0) throw new IllegalArgumentException("limits must be >= 0");
        for (Duration d : new Duration[]{approvalTimeout, stageTimeout, rollbackTimeout}) {
            if (d == null || d.isNegative() || d.isZero() || d.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("timeouts must be in (0, 1 day]");
            }
        }
    }

    public static RunOptions defaults() {
        return new RunOptions(3, 0, Duration.ofMinutes(30), Duration.ofMinutes(10), Duration.ofSeconds(30));
    }

    public RunOptions withMaxReplans(int v) { return new RunOptions(v, failureBudget, approvalTimeout, stageTimeout, rollbackTimeout); }
    public RunOptions withFailureBudget(int v) { return new RunOptions(maxReplans, v, approvalTimeout, stageTimeout, rollbackTimeout); }
    public RunOptions withApprovalTimeout(Duration v) { return new RunOptions(maxReplans, failureBudget, v, stageTimeout, rollbackTimeout); }
    public RunOptions withStageTimeout(Duration v) { return new RunOptions(maxReplans, failureBudget, approvalTimeout, v, rollbackTimeout); }
    public RunOptions withRollbackTimeout(Duration v) { return new RunOptions(maxReplans, failureBudget, approvalTimeout, stageTimeout, v); }
}
