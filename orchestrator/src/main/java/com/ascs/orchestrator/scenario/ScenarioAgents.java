package com.ascs.orchestrator.scenario;

import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.planner.CodebaseImpactAnalyzer;
import com.ascs.orchestrator.planner.NormalizedRequirement;

import java.util.Map;

/** Agents and fault injectors used by the scenarios: they make failure modes reproducible. */
final class ScenarioAgents {
    private ScenarioAgents() {}

    /** AWS's documented dummy key id: matches the secret pattern but is not a real credential. */
    // review:accepted CR-01 AWS documented example key id (not a credential), used to exercise SecretLeakPolicy
    static final String DUMMY_AWS_KEY = "AKIAIOSFODNN7EXAMPLE";

    static Agent requirements(NormalizedRequirement n) {
        return in -> new AgentResult(Map.of("requirements.md", n.toMarkdown()), "requirement normalized and decomposed");
    }

    static Agent clarification(NormalizedRequirement n) {
        return in -> {
            StringBuilder sb = new StringBuilder("# Clarifications needed\n\n");
            for (int i = 0; i < n.ambiguities().size(); i++) {
                sb.append("Q").append(i + 1).append(": ").append(n.ambiguities().get(i)).append('\n')
                        .append("   Proposed default: ").append(n.assumptions().get(i)).append("\n\n");
            }
            return new AgentResult(Map.of("clarifications.md", sb.toString()),
                    n.ambiguities().size() + " ambiguities need a human decision before design starts");
        };
    }

    static Agent impactAnalysis(CodebaseImpactAnalyzer analyzer, String... symbols) {
        return in -> {
            String body = analyzer.available() ? analyzer.analyze(symbols).toMarkdown()
                    : "# Impact analysis\n\nSource tree not available at run time; impact must be assessed manually.\n";
            return new AgentResult(Map.of("impact-analysis.md", body), "analyzed references to " + String.join(", ", symbols));
        };
    }

    /** Throws on every attempt before {@code succeedOnAttempt}; models a transient fault. */
    static Agent failUntilAttempt(int succeedOnAttempt, Agent delegate, String message) {
        return in -> {
            if (in.attempt() < succeedOnAttempt) throw new IllegalStateException(message);
            return delegate.execute(in);
        };
    }

    static Agent alwaysFail(String message) {
        return in -> { throw new IllegalStateException(message); };
    }

    /** Models an agent that pastes a credential into its output on early attempts. */
    static Agent leakSecretUntilAttempt(int cleanFromAttempt, String outputName, Agent clean) {
        return in -> {
            if (in.attempt() >= cleanFromAttempt) return clean.execute(in);
            return new AgentResult(Map.of(outputName, "+ cassandra.password-config\n+ aws_access_key_id=" + DUMMY_AWS_KEY),
                    "implemented with embedded credentials");
        };
    }
}
