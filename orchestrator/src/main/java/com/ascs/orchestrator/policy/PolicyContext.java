package com.ascs.orchestrator.policy;

import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.model.StageDefinition;

import java.util.Map;

/** What a policy may inspect: the stage, its proposed (uncommitted) outputs, and the committed store. */
public record PolicyContext(StageDefinition stage, Map<String, String> outputs, ArtifactStore store) {
}
