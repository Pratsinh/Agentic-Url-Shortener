package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.LlmLedger;
import com.ascs.agentic.model.Models;
import com.fasterxml.jackson.databind.JsonNode;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.Locale;
import java.util.Map;

/**
 * Deterministic agent: assembles the evidence a human needs for the release decision (criteria vs review, real
 * test results vs baseline, security scan, documentation, diff, model usage) into one checklist. The stage is
 * HIGH impact, so the engine refuses to commit it without a human approval; the commit stage runs only after.
 */
public final class ReleaseReadinessAgent implements Agent {

    public static final String MD = "release-readiness.md";

    private final AgentKit kit;
    private final DeliveryContext ctx;

    public ReleaseReadinessAgent(AgentKit kit, DeliveryContext ctx) {
        this.kit = kit;
        this.ctx = ctx;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        Models.Review review = AgentKit.upstream(in, ReviewerAgent.JSON, Models.Review.class);
        JsonNode verification = Json.parse(AgentKit.content(in, VerificationAgent.VERIFY_JSON));
        JsonNode security = Json.parse(AgentKit.content(in, SecurityScanAgent.JSON));
        JsonNode tests = verification.path("tests");
        long majors = review.findings().stream().filter(f -> f.severity().equals("MAJOR")).count();
        LlmLedger.Snapshot llm = kit.ledger().snapshot();
        String diffStat = kit.workspace().diffStat();

        StringBuilder md = new StringBuilder("# Release readiness: ").append(ctx.plan().summary()).append("\n\n");
        md.append("**Branch:** `").append(kit.workspace().branch()).append("` from base `").append(kit.workspace().baseCommit(), 0, 12).append("`\n\n");
        md.append("## Checklist\n| Check | Result |\n|---|---|\n");
        md.append(row("Approved plan executed (hash-verified)", ctx.planArtifact().ref() + " sha256 " + ctx.planArtifact().contentHash().substring(0, 12)));
        md.append(row("Full test suite (real Maven run)", tests.path("tests").asInt() + " tests, " + tests.path("failures").asInt()
                + " failures, " + tests.path("errors").asInt() + " errors; baseline " + verification.path("baseline").path("tests").asInt()
                + " (+" + verification.path("testsAdded").asInt() + ")"
                + (tests.path("lineCoverage").isNumber() ? String.format(Locale.ROOT, ", line coverage %.1f%%", tests.path("lineCoverage").asDouble() * 100) : "")));
        md.append(row("Independent review", review.verdict() + " (" + review.findings().size() + " findings, " + majors + " MAJOR)"));
        md.append(row("Acceptance criteria met (per review)", review.criteria().stream().filter(Models.CriterionCheck::met).count()
                + "/" + ctx.analysis().acceptanceCriteria().size()));
        md.append(row("Security scan", security.path("findings").size() + " finding(s), " + security.path("blocking").asInt() + " blocking"));
        md.append(row("Documentation", DocsAgent.docPath(in.runId())));
        md.append(row("Model usage so far", llm.calls() + " calls, " + llm.usage().inputTokens() + " in / " + llm.usage().outputTokens()
                + " out tokens, cache read " + llm.usage().cacheReadTokens() + String.format(Locale.ROOT, ", est. $%.2f", llm.estimatedCostUsd())));
        md.append("\n## Acceptance criteria\n");
        for (Models.CriterionCheck c : review.criteria()) {
            md.append("- ").append(c.met() ? "[x] " : "[ ] ").append(c.id()).append(": ").append(c.evidence()).append('\n');
        }
        if (majors > 0) {
            md.append("\n## Open MAJOR findings (not blocking, need a decision)\n");
            review.findings().stream().filter(f -> f.severity().equals("MAJOR"))
                    .forEach(f -> md.append("- `").append(f.file()).append("` ").append(f.title()).append(": ").append(f.detail()).append('\n'));
        }
        md.append("\n## Change\n```\n").append(diffStat).append("```\n\n```diff\n")
                .append(AgentKit.truncate(kit.workspace().diff(), 30_000)).append("\n```\n");
        md.append("\nApproving commits this change to `").append(kit.workspace().branch()).append("` and, when pull requests are enabled,")
                .append(" pushes that branch and opens a pull request for review. Merging remains a separate human decision.\n");
        return new AgentResult(Map.of(MD, md.toString()), "release evidence assembled: review " + review.verdict() + ", "
                + tests.path("tests").asInt() + " tests passing, security blocking=" + security.path("blocking").asInt());
    }

    private static String row(String check, String result) {
        return "| " + check + " | " + result.replace("|", "/") + " |\n";
    }
}
