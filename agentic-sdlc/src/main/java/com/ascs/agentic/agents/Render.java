package com.ascs.agentic.agents;

import com.ascs.agentic.model.Models;

import java.util.List;

/** Markdown views of the agents' structured results, for human reviewers and the run report. */
public final class Render {
    private Render() {}

    public static String analysis(Models.RequirementAnalysis a) {
        StringBuilder sb = new StringBuilder("# Requirement analysis: ").append(a.title()).append("\n\n");
        sb.append("**Classification:** ").append(a.kind()).append("\n\n**Engineering problem:** ").append(a.problemStatement()).append("\n\n");
        sb.append("## Acceptance criteria\n");
        a.acceptanceCriteria().forEach(c -> sb.append("- **").append(c.id()).append("** ").append(c.text()).append('\n'));
        sb.append("\n## Ambiguities\n");
        if (a.ambiguities().isEmpty()) sb.append("None identified.\n");
        a.ambiguities().forEach(m -> sb.append("- **").append(m.id()).append("**").append(m.blocking() ? " (blocking)" : "")
                .append(": ").append(m.question()).append("\n  - why it matters: ").append(m.whyItMatters())
                .append("\n  - proposed assumption: ").append(m.proposedAssumption()).append('\n'));
        sb.append("\n## Impacted areas (verified against the repository)\n| Path | Change | Reason |\n|---|---|---|\n");
        a.impactedAreas().forEach(i -> sb.append("| `").append(i.path()).append("` | ").append(i.changeType()).append(" | ")
                .append(cell(i.reason())).append(" |\n"));
        sb.append("\n## Risks\n");
        a.risks().forEach(r -> sb.append("- ").append(r.description()).append(" -> ").append(r.mitigation()).append('\n'));
        sb.append("\n## Out of scope\n");
        a.outOfScope().forEach(o -> sb.append("- ").append(o).append('\n'));
        return sb.toString();
    }

    public static String guidance(Models.Clarifications c) {
        StringBuilder sb = new StringBuilder("# Human guidance (binding)\n\n");
        if (c.resolutions().isEmpty()) return sb.append("No clarifications were needed.\n").toString();
        sb.append("Resolved by **").append(c.respondedBy()).append("** via ").append(c.channel()).append(".\n\n");
        for (Models.Resolution r : c.resolutions()) {
            sb.append("- **").append(r.id()).append("** ").append(r.question()).append("\n  - ")
                    .append(r.decision().equals("ANSWERED") ? "Answer: " : "Approved assumption: ").append(r.text()).append('\n');
        }
        return sb.toString();
    }

    public static String plan(Models.Plan p) {
        StringBuilder sb = new StringBuilder("# Plan: ").append(p.summary()).append("\n\n## Design\n").append(p.design()).append("\n\n");
        sb.append("## Tasks (dependency graph)\n| Task | Title | Depends on | Files | Criteria |\n|---|---|---|---|---|\n");
        for (Models.PlanTask t : p.tasks()) {
            sb.append("| ").append(t.id()).append(" | ").append(cell(t.title())).append(" | ")
                    .append(t.dependsOn().isEmpty() ? "-" : String.join(", ", t.dependsOn())).append(" | ")
                    .append(String.join("<br>", t.files().stream().map(f -> "`" + f + "`").toList())).append(" | ")
                    .append(String.join(", ", t.acceptanceCriteria())).append(" |\n");
        }
        sb.append('\n');
        for (Models.PlanTask t : p.tasks()) sb.append("**").append(t.id()).append("**: ").append(t.description()).append("\n\n");
        sb.append("## Test strategy\n").append(p.testStrategy()).append("\n\n## Compatibility\n").append(p.compatibility())
                .append("\n\n## Rollback\n").append(p.rollback()).append('\n');
        List<String> adj = p.orchestratorAdjustments() == null ? List.of() : p.orchestratorAdjustments();
        if (!adj.isEmpty()) {
            sb.append("\n## Orchestrator adjustments (deterministic, not model output)\n");
            adj.forEach(a -> sb.append("- ").append(a).append('\n'));
        }
        return sb.toString();
    }

    public static String review(Models.Review r) {
        StringBuilder sb = new StringBuilder("# Independent review: ").append(r.verdict()).append("\n\n").append(r.summary()).append("\n\n");
        sb.append("## Acceptance criteria\n| Criterion | Met | Evidence |\n|---|---|---|\n");
        r.criteria().forEach(c -> sb.append("| ").append(c.id()).append(" | ").append(c.met() ? "yes" : "**no**").append(" | ")
                .append(cell(c.evidence())).append(" |\n"));
        sb.append("\n## Findings\n");
        if (r.findings().isEmpty()) sb.append("None.\n");
        r.findings().forEach(f -> sb.append("- **").append(f.severity()).append("** `").append(f.file())
                .append(f.line() > 0 ? ":" + f.line() : "").append("` ").append(f.title()).append(": ").append(f.detail())
                .append(f.recommendation().isBlank() ? "" : " Recommendation: " + f.recommendation()).append('\n'));
        return sb.toString();
    }

    static String cell(String s) {
        return s.replace("|", "/").replace("\n", " ");
    }
}
