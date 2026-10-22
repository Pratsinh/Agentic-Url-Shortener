package com.ascs.orchestrator.model;

import java.util.Map;

/** What an agent returns: named outputs plus a human-readable rationale (kept in the audit log). */
public record AgentResult(Map<String, String> outputs, String rationale) {
}
