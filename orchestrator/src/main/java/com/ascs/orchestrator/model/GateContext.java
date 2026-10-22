package com.ascs.orchestrator.model;

import com.ascs.orchestrator.core.ArtifactStore;

import java.util.Map;

/** Entry gates see the store (outputs empty); exit gates also see the not-yet-committed outputs. */
public record GateContext(ArtifactStore store, Map<String, String> outputs) {
}
