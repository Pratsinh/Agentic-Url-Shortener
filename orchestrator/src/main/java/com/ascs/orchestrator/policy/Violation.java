package com.ascs.orchestrator.policy;

/** A policy finding. The message must never echo the offending content (it goes into the audit log). */
public record Violation(String policyId, PolicyCategory category, Severity severity, String message) {
}
