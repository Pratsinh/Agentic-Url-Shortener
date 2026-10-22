package com.ascs.orchestrator.sdlc;

import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.Artifact;

import com.ascs.orchestrator.planner.Task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, offline agents. They let the whole system run end-to-end without an LLM and make
 * orchestration behaviour (ordering, gates, lineage) testable. An LLM-backed agent implements the
 * same {@link Agent} interface and is a drop-in replacement.
 */
public final class StubAgents {
    private StubAgents() {}

    public static Map<String, Agent> deterministic() {
        Map<String, Agent> m = new LinkedHashMap<>();
        m.put("requirements", templated("requirements.md", "Normalized requirements"));
        m.put("design", templated("design.md", "Design"));
        m.put("implementation", templated("code.patch", "Implementation"));
        m.put("testing", templated("test-report.md", "Test report"));
        m.put("security-review", templated("security-report.md", "Security report"));
        m.put("documentation", templated("docs.md", "Documentation"));
        m.put("release-readiness", templated("release-checklist.md", "Release checklist"));
        return m;
    }

    /** One deterministic agent per task (plus any declared fallback agents). */
    public static Map<String, Agent> forTasks(List<Task> tasks) {
        Map<String, Agent> m = new LinkedHashMap<>();
        for (Task t : tasks) {
            m.put(t.id(), templated(t.output(), t.title()));
            if (t.fallbackAgent() != null) m.put(t.fallbackAgent(), templated(t.output(), t.title() + " (fallback template)"));
        }
        return m;
    }

    public static Agent templated(String outputName, String title) {
        return input -> {
            StringBuilder sb = new StringBuilder("# ").append(title).append('\n')
                    .append("requirement: ").append(input.requirement()).append('\n');
            for (Artifact a : input.upstream()) {
                sb.append("- based on ").append(a.ref()).append(" sha=").append(a.contentHash(), 0, 8).append('\n');
            }
            return new AgentResult(Map.of(outputName, sb.toString()), title + " generated deterministically");
        };
    }
}
