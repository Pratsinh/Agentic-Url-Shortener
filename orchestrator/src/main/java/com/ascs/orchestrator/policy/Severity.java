package com.ascs.orchestrator.policy;

/** How the engine reacts to a policy violation. */
public enum Severity {
    /** Recorded only. */
    INFO,
    /** Recorded and surfaced to the human reviewer. */
    WARN,
    /** Output is rejected (counts as a failed attempt; retry/fallback may follow). */
    BLOCK,
    /** Output is rejected AND the run safe-stops: the agent misbehaved in a way that must not be retried. */
    CRITICAL
}
