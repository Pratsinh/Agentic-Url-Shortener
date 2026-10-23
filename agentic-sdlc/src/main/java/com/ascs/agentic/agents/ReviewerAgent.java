package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Independent LLM reviewer: a fresh conversation with its own role prompt and only read access (it cannot change
 * the code it judges). It sees the requirement, criteria, approved plan, the real diff and the real test results,
 * and must assess every acceptance criterion with evidence. The stage's exit gate requires an APPROVE verdict;
 * a REQUEST_CHANGES review is kept so the {@link ReviewFixAgent} fallback can address its findings.
 */
public final class ReviewerAgent implements Agent {

    public static final String ROLE = "reviewer";
    public static final String JSON = "review.json", MD = "review.md";
    private static final int MAX_DIFF = 90_000;

    private final AgentKit kit;
    private final DeliveryContext ctx;
    private final Map<String, Models.Review> lastReview = new ConcurrentHashMap<>();

    public ReviewerAgent(AgentKit kit, DeliveryContext ctx) {
        this.kit = kit;
        this.ctx = ctx;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        return review(in, AgentKit.content(in, VerificationAgent.PATCH), AgentKit.content(in, VerificationAgent.VERIFY_JSON), "");
    }

    AgentResult review(StageInput in, String diff, String verification, String note) throws Exception {
        String prompt = "Requirement:\n<requirement>\n" + ctx.requirement() + "\n</requirement>\n\n## Acceptance criteria\n"
                + ctx.criteriaText() + "\n" + ctx.guidance() + "\n\n## Approved plan\n" + Render.plan(ctx.plan())
                + "\n\n## Verification (real build and test results)\n" + AgentKit.truncate(verification, 6_000)
                + "\n\n## Diff under review (against the base commit)\n```diff\n" + AgentKit.truncate(diff, MAX_DIFF) + "\n```\n"
                + note + "\n" + kit.conventions();
        AgentLoop.Outcome<Models.Review> out = kit.loop().run(in, ROLE, kit.config().reviewModel(), kit.config().effort(),
                kit.config().maxTokens(), AgentKit.systemPrompt(ROLE), prompt,
                new AgentLoop.Terminal<>("submit_review", "Submit the review verdict, findings and per-criterion assessment.",
                        Json.resource("schemas/review.schema.json"), Models.Review.class, this::check));
        Models.Review r = out.value();
        lastReview.put(in.runId(), r);
        long blockers = r.findings().stream().filter(f -> f.severity().equals("BLOCKER")).count();
        return new AgentResult(Map.of(JSON, Json.pretty(r), MD, Render.review(r)), "verdict=" + r.verdict() + " blockers="
                + blockers + " findings=" + r.findings().size() + " criteriaMet=" + r.criteria().stream().filter(Models.CriterionCheck::met).count()
                + "/" + r.criteria().size() + " turns=" + out.turns());
    }

    List<String> check(Models.Review r) {
        List<String> errors = new ArrayList<>();
        Set<String> assessed = new HashSet<>();
        r.criteria().forEach(c -> assessed.add(c.id()));
        for (Models.Criterion c : ctx.analysis().acceptanceCriteria()) {
            if (!assessed.contains(c.id())) errors.add("criterion " + c.id() + " is not assessed");
        }
        boolean blockers = r.findings().stream().anyMatch(f -> f.severity().equals("BLOCKER"));
        boolean unmet = r.criteria().stream().anyMatch(c -> !c.met());
        if (r.verdict().equals("APPROVE") && (blockers || unmet)) {
            errors.add("verdict APPROVE is inconsistent with BLOCKER findings or unmet criteria");
        }
        if (r.verdict().equals("REQUEST_CHANGES") && r.findings().isEmpty()) errors.add("REQUEST_CHANGES needs at least one finding");
        return errors;
    }

    Models.Review lastReview(String runId) {
        return lastReview.get(runId);
    }

    /** Exit gate: the change may proceed only with an APPROVE verdict. */
    public static String gateFailure(String reviewJson) {
        Models.Review r = Json.read(reviewJson, Models.Review.class);
        return r.verdict().equals("APPROVE") ? null : "reviewer requested changes: " + r.summary();
    }
}
