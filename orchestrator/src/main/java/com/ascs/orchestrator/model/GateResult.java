package com.ascs.orchestrator.model;

/** Outcome of one gate evaluation; {@code reason} explains a failure. */
public record GateResult(boolean passed, String reason) {
    public static GateResult pass() { return new GateResult(true, "ok"); }
    public static GateResult fail(String reason) { return new GateResult(false, reason); }
}
