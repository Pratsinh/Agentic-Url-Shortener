package com.ascs.orchestrator.tools;

/** What a tool call can do to the target system; decides whether a human must approve it and whether it may be retried. */
public enum Risk {
    /** Reads only: allowed after policy checks, retried on transient failures. */
    READ_ONLY,
    /** Changes the target system: needs human approval and is never retried automatically. */
    MUTATING
}
