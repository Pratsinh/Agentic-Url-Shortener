package com.ascs.orchestrator.model;

/** Lifecycle state of a stage within a run. */
public enum StageStatus {
    PENDING,
    RUNNING,
    /** Output produced and validated; waiting for a human decision before it is committed. */
    AWAITING_APPROVAL,
    SUCCEEDED,
    /** Agent error, exit gate denied, policy violation, or timeout, after retries/fallback were exhausted. */
    FAILED,
    /** Never ran (or was cancelled): upstream failed/blocked, entry gate denied, or the run was halted. */
    BLOCKED,
    /** A human (or an approval timeout) said no. */
    REJECTED,
    /** Outputs revoked and compensation (if any) completed. */
    ROLLED_BACK,
    /** Compensation failed: needs manual remediation. */
    ROLLBACK_FAILED
}
