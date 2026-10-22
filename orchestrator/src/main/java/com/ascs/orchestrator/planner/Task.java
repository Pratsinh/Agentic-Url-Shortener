package com.ascs.orchestrator.planner;

import com.ascs.orchestrator.model.FailureStrategy;
import com.ascs.orchestrator.model.Impact;

import java.util.List;

/** One unit of decomposed work. Becomes exactly one workflow stage, so decomposition and DAG cannot drift apart. */
public record Task(String id, String title, List<String> dependsOn, String output, Impact impact,
                   boolean requiresApproval, int maxAttempts, boolean compensable,
                   FailureStrategy onFailure, String fallbackAgent) {

    public Task {
        dependsOn = List.copyOf(dependsOn);
    }

    public static Task of(String id, String title, String output, String... deps) {
        return new Task(id, title, List.of(deps), output, Impact.LOW, false, 1, false,
                FailureStrategy.BLOCK_DESCENDANTS, null);
    }

    /** HIGH impact always implies human approval (the engine refuses HIGH without it). */
    public Task highImpact() {
        return new Task(id, title, dependsOn, output, Impact.HIGH, true, maxAttempts, compensable, onFailure, fallbackAgent);
    }

    public Task retries(int n) {
        return new Task(id, title, dependsOn, output, impact, requiresApproval, n, compensable, onFailure, fallbackAgent);
    }

    public Task asCompensable() {
        return new Task(id, title, dependsOn, output, impact, requiresApproval, maxAttempts, true, onFailure, fallbackAgent);
    }

    public Task rollbackRunOnFailure() {
        return new Task(id, title, dependsOn, output, impact, requiresApproval, maxAttempts, compensable,
                FailureStrategy.ROLLBACK_RUN, fallbackAgent);
    }

    public Task withFallback(String agent) {
        return new Task(id, title, dependsOn, output, impact, requiresApproval, maxAttempts, compensable, onFailure, agent);
    }
}
