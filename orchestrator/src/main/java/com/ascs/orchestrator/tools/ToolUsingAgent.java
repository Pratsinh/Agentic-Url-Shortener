package com.ascs.orchestrator.tools;

import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An agent that works through tools: it loops planner -> gateway -> planner until the planner finishes, bounded by
 * {@code maxSteps} so a confused planner cannot loop forever (exceeding it fails the attempt). The planner sees only
 * the tools granted to this stage, and its outputs go through the engine's gates and policies like any agent's.
 */
public final class ToolUsingAgent implements Agent {

    private final ToolGateway gateway;
    private final ToolPlanner planner;
    private final int maxSteps;

    public ToolUsingAgent(ToolGateway gateway, ToolPlanner planner, int maxSteps) {
        this.gateway = gateway;
        this.planner = planner;
        this.maxSteps = maxSteps;
    }

    @Override
    public AgentResult execute(StageInput input) throws Exception {
        ToolGateway.Context ctx = new ToolGateway.Context(input.runId(), input.stageId(), input.attempt());
        List<ToolSpec> tools = gateway.toolsFor(input.stageId());
        List<ToolResult> results = new ArrayList<>();
        for (int step = 0; step < maxSteps; step++) {
            switch (planner.next(input, tools, List.copyOf(results))) {
                case ToolPlanner.Finish f -> {
                    String calls = results.stream().map(r -> r.tool() + "=" + r.status()).collect(Collectors.joining(", "));
                    return new AgentResult(f.outputs(), f.rationale() + " [tool calls: " + (calls.isEmpty() ? "none" : calls) + "]");
                }
                case ToolPlanner.Call c -> results.add(gateway.invoke(ctx, c.call()));
            }
        }
        throw new IllegalStateException("planner did not finish within " + maxSteps + " steps");
    }
}
