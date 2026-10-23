package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.HashMap;
import java.util.Map;

/**
 * Fallback of the {@code review} stage (one bounded review-fix-re-review iteration): an LLM engineer addresses the
 * reviewer's BLOCKER and MAJOR findings within the plan's write scope (tool calls made as stage {@value #TOOL_STAGE}),
 * the full test suite runs again, and a fresh independent review decides. The exit gate still requires APPROVE.
 */
public final class ReviewFixAgent implements Agent {

    public static final String TOOL_STAGE = "review-fix";

    private final AgentKit kit;
    private final DeliveryContext ctx;
    private final ReviewerAgent reviewer;

    public ReviewFixAgent(AgentKit kit, DeliveryContext ctx, ReviewerAgent reviewer) {
        this.kit = kit;
        this.ctx = ctx;
        this.reviewer = reviewer;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        Models.Review previous = reviewer.lastReview(in.runId());
        if (previous == null) throw new IllegalStateException("no review to address");
        String prompt = "An independent reviewer requested changes to the implementation in this workspace. Address every "
                + "BLOCKER and MAJOR finding (or explain in notes why a finding is wrong), keep all tests passing, and do not "
                + "weaken tests.\n\n" + Render.review(previous) + "\n## Acceptance criteria\n" + ctx.criteriaText()
                + "\n## Your write scope\n" + ctx.unionScope().describe() + "\n\n" + kit.conventions();
        StageInput asFix = new StageInput(in.runId(), TOOL_STAGE, in.requirement(), in.upstream(), in.attempt());
        AgentLoop.Outcome<Models.ImplementationResult> out = kit.loop().run(asFix, "implementer", kit.config().model(),
                kit.config().effort(), kit.config().maxTokens(), AgentKit.systemPrompt("implementer"), prompt,
                new ImplementerAgent(kit, ctx, ctx.plan().tasks().get(0).id()).terminal());
        AgentResult verified = VerificationAgent.verify(kit, in, "review-fix");
        AgentResult rereview = reviewer.review(in, verified.outputs().get(VerificationAgent.PATCH),
                verified.outputs().get(VerificationAgent.VERIFY_JSON),
                "\nNote: this is a re-review after fixes for an earlier review that requested changes:\n" + Render.review(previous));
        Map<String, String> outputs = new HashMap<>(rereview.outputs());
        outputs.put("review-fix.json", Json.pretty(Map.of("previousVerdict", previous.verdict(), "fix", out.value(),
                "verification", verified.rationale(), "modelTurns", out.turns())));
        outputs.put("review-fix.patch", verified.outputs().get(VerificationAgent.PATCH));
        return new AgentResult(outputs, "fixed review findings (" + verified.rationale() + "); re-review: " + rereview.rationale());
    }
}
