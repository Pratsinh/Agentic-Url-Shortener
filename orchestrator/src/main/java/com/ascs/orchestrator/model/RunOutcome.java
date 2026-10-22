package com.ascs.orchestrator.model;

/** Terminal outcome of a whole workflow run. */
public enum RunOutcome {
    SUCCEEDED,
    /** Some stage failed/was rejected; unaffected branches may have completed. */
    FAILED,
    /** Run halted and every completed stage was undone. */
    ROLLED_BACK,
    /** Run halted for rollback but at least one compensation failed: manual remediation required. */
    ROLLBACK_INCOMPLETE,
    /** Run frozen by kill switch, critical policy violation, or exhausted budget. */
    SAFE_STOPPED,
    /** Coordinator interrupted. */
    ABORTED
}
