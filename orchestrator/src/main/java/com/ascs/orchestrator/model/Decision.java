package com.ascs.orchestrator.model;

/** A human's answer to an approval request. */
public record Decision(boolean approved, String approver, String comment) {
    public static Decision approve(String approver, String comment) { return new Decision(true, approver, comment); }
    public static Decision reject(String approver, String comment) { return new Decision(false, approver, comment); }
}
