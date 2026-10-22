package com.ascs.orchestrator.model;

import java.util.List;

/** Compensating action for a stage with real-world side effects (e.g. revert an applied patch). Must be idempotent. */
@FunctionalInterface
public interface RollbackAction {

    record Context(String runId, String stageId, List<ArtifactRef> artifacts) {
    }

    void rollback(Context ctx) throws Exception;
}
