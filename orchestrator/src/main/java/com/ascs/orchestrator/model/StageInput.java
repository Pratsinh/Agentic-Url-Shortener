package com.ascs.orchestrator.model;

import java.util.List;
import java.util.Optional;

/** Immutable snapshot handed to an agent: the requirement, ancestor artifacts only, and the 1-based attempt number. */
public record StageInput(String runId, String stageId, String requirement, List<Artifact> upstream, int attempt) {

    public StageInput {
        upstream = List.copyOf(upstream);
    }

    public Optional<Artifact> artifact(String name) {
        return upstream.stream().filter(a -> a.name().equals(name)).findFirst();
    }
}
