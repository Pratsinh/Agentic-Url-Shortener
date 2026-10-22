package com.ascs.orchestrator.planner;

import java.util.List;

/** The requirement restated as an engineering problem, with open questions, assumptions and a task decomposition. */
public record NormalizedRequirement(String original, Kind kind, String problemStatement,
                                    List<String> ambiguities, List<String> assumptions, List<Task> tasks) {

    public enum Kind { GREENFIELD, BROWNFIELD, AMBIGUOUS }

    public NormalizedRequirement {
        ambiguities = List.copyOf(ambiguities);
        assumptions = List.copyOf(assumptions);
        tasks = List.copyOf(tasks);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("# Normalized requirement\n\n");
        sb.append("**Original:** ").append(original).append("\n\n");
        sb.append("**Classification:** ").append(kind).append("\n\n");
        sb.append("**Engineering problem:** ").append(problemStatement).append("\n\n");
        if (!ambiguities.isEmpty()) {
            sb.append("## Open questions\n");
            ambiguities.forEach(a -> sb.append("- ").append(a).append('\n'));
            sb.append('\n');
        }
        sb.append("## Assumptions\n");
        assumptions.forEach(a -> sb.append("- ").append(a).append('\n'));
        sb.append("\n## Task decomposition\n");
        tasks.forEach(t -> sb.append("- ").append(t.id()).append(" (").append(t.title()).append(")")
                .append(t.dependsOn().isEmpty() ? "" : " after " + t.dependsOn()).append('\n'));
        return sb.toString();
    }
}
