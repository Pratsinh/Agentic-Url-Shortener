package com.ascs.orchestrator.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Declarative node of the workflow graph: dependencies, gates, and its governance/resilience settings. */
public record StageDefinition(String id, String description, String agentName,
                              Set<String> dependsOn, List<Gate> entryGates, List<Gate> exitGates,
                              RetryPolicy retry, String fallbackAgent, Impact impact,
                              boolean requiresApproval, RollbackAction rollback, FailureStrategy onFailure) {

    public StageDefinition {
        dependsOn = Set.copyOf(dependsOn);
        entryGates = List.copyOf(entryGates);
        exitGates = List.copyOf(exitGates);
        Objects.requireNonNull(retry);
        Objects.requireNonNull(impact);
        Objects.requireNonNull(onFailure);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String description = "";
        private String agentName;
        private final Set<String> deps = new LinkedHashSet<>();
        private final List<Gate> entry = new ArrayList<>();
        private final List<Gate> exit = new ArrayList<>();
        private RetryPolicy retry = RetryPolicy.NONE;
        private String fallbackAgent;
        private Impact impact = Impact.LOW;
        private boolean requiresApproval;
        private RollbackAction rollback;
        private FailureStrategy onFailure = FailureStrategy.BLOCK_DESCENDANTS;

        private Builder(String id) { this.id = id; this.agentName = id; }

        public Builder describe(String d) { this.description = d; return this; }
        public Builder agent(String name) { this.agentName = name; return this; }
        public Builder dependsOn(String... ids) { deps.addAll(List.of(ids)); return this; }
        public Builder entryGate(Gate g) { entry.add(g); return this; }
        public Builder exitGate(Gate g) { exit.add(g); return this; }
        public Builder retry(RetryPolicy r) { this.retry = r; return this; }
        public Builder fallback(String agentName) { this.fallbackAgent = agentName; return this; }
        public Builder impact(Impact i) { this.impact = i; return this; }
        public Builder requireApproval() { this.requiresApproval = true; return this; }
        public Builder rollback(RollbackAction a) { this.rollback = a; return this; }
        public Builder onFailure(FailureStrategy s) { this.onFailure = s; return this; }

        public StageDefinition build() {
            return new StageDefinition(id, description, agentName, deps, entry, exit,
                    retry, fallbackAgent, impact, requiresApproval, rollback, onFailure);
        }
    }
}
