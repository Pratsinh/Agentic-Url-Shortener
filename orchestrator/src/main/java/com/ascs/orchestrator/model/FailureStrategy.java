package com.ascs.orchestrator.model;

/** What the run does when a stage fails for good (after retries and fallback). */
public enum FailureStrategy {
    /** Default: block this stage's descendants; independent branches continue. */
    BLOCK_DESCENDANTS,
    /** Halt the run and undo every completed stage (compensation + artifact revocation). */
    ROLLBACK_RUN,
    /** Halt the run immediately and freeze state for a human to inspect. */
    SAFE_STOP
}
