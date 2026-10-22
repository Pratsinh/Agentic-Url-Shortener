package com.ascs.orchestrator.model;

/** Immutable pointer to one version of an artifact. */
public record ArtifactRef(String name, int version) {
    @Override
    public String toString() {
        return name + "@" + version;
    }
}
