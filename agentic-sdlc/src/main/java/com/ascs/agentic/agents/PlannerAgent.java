package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.Map;

/**
 * LLM agent: design and task decomposition. The proposal must pass {@link PlanValidator} inside the loop (the model
 * is told what is wrong and resubmits); the orchestrator then serializes tasks that share files. The plan stage
 * requires human approval, and a reviewer may answer "revise" to have it re-planned with their feedback.
 */
public final class PlannerAgent implements Agent {

    public static final String ROLE = "planner";
    public static final String JSON = "plan.json", MD = "plan.md";

    private final AgentKit kit;

    public PlannerAgent(AgentKit kit) {
        this.kit = kit;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        Models.RequirementAnalysis analysis = AgentKit.upstream(in, RequirementAnalystAgent.JSON, Models.RequirementAnalysis.class);
        String prompt = "Requirement:\n<requirement>\n" + in.requirement() + "\n</requirement>\n\n"
                + AgentKit.content(in, RequirementAnalystAgent.MD) + "\n\n" + AgentKit.content(in, ClarificationAgent.GUIDANCE)
                + "\n\nWritable modules: " + kit.config().writableModules() + "\n\n" + kit.conventions();
        AgentLoop.Outcome<Models.Plan> out = kit.loop().run(in, ROLE, kit.config().model(), kit.config().effort(),
                kit.config().maxTokens(), AgentKit.systemPrompt(ROLE), prompt,
                new AgentLoop.Terminal<>("submit_plan", "Submit the design and task dependency graph.",
                        Json.resource("schemas/plan.schema.json"), Models.Plan.class,
                        p -> PlanValidator.validate(p, analysis, kit.config().writableModules())));
        Models.Plan plan = PlanValidator.serializeSharedFiles(out.value());
        return new AgentResult(Map.of(JSON, Json.pretty(plan), MD, Render.plan(plan)),
                plan.tasks().size() + " tasks, " + plan.orchestratorAdjustments().size() + " orchestrator adjustment(s), turns="
                        + out.turns() + ": " + plan.summary());
    }
}
