package com.ascs.agentic.agents;

import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.agentic.workspace.TestReport;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

/**
 * Fallback of the {@code verify} stage: when the integrated change fails its tests, an LLM repair engineer gets the
 * real failure output and the union of the tasks' write scopes (its tool calls are made as stage
 * {@value #TOOL_STAGE}), then the full verification runs again. If that still fails, the stage fails and the run
 * rolls back.
 */
public final class RepairAgent implements Agent {

    public static final String ROLE = "repair", TOOL_STAGE = "verify-repair";

    private final AgentKit kit;
    private final DeliveryContext ctx;

    public RepairAgent(AgentKit kit, DeliveryContext ctx) {
        this.kit = kit;
        this.ctx = ctx;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        TestReport failing = kit.build().runTests(kit.workspace().root(), kit.config().verifyModule(), null);
        if (failing.passed()) return VerificationAgent.verify(kit, in, "repair (tests already passing on re-run)");
        String prompt = "The integrated change fails verification.\n\n## Build result\n" + failing.summary() + "\n"
                + String.join("\n", failing.failedTests()) + "\n\n## Build output (tail)\n" + failing.outputTail()
                + "\n\n## Acceptance criteria\n" + ctx.criteriaText() + "\n## Approved design\n" + ctx.plan().design()
                + "\n\n## Your write scope\n" + ctx.unionScope().describe() + "\n\n" + kit.conventions();
        StageInput asRepair = new StageInput(in.runId(), TOOL_STAGE, in.requirement(), in.upstream(), in.attempt());
        AgentLoop.Outcome<Models.ImplementationResult> out = kit.loop().run(asRepair, ROLE, kit.config().model(),
                kit.config().effort(), kit.config().maxTokens(), AgentKit.systemPrompt(ROLE), prompt,
                new ImplementerAgent(kit, ctx, ctx.plan().tasks().get(0).id()).terminal());
        AgentResult verified = VerificationAgent.verify(kit, in, "repair agent (" + out.value().summary() + ")");
        return new AgentResult(verified.outputs(), verified.rationale() + "; repair turns=" + out.turns());
    }
}
