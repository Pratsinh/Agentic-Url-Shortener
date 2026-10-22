package com.ascs.orchestrator.model;

import java.time.Instant;
import java.util.List;

/**
 * A versioned output of a stage. {@code inputs} records exactly which upstream artifact versions
 * were visible to the producing stage: this is the decision lineage.
 */
public record Artifact(String name, int version, String content, String producerStage,
                       List<ArtifactRef> inputs, String contentHash, Instant createdAt) {

    public Artifact {
        inputs = List.copyOf(inputs);
    }

    public ArtifactRef ref() {
        return new ArtifactRef(name, version);
    }
}
