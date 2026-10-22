package com.ascs.orchestrator.tools;

import com.ascs.orchestrator.model.StageInput;

import java.util.List;
import java.util.Map;

/**
 * The decision-maker inside a {@link ToolUsingAgent}: this is where an LLM plugs in. Given the stage input, the
 * tools the stage is granted and the results so far, it proposes the next tool call or finishes with the stage's
 * outputs. It only proposes: the gateway decides about calls and the engine about outputs.
 */
@FunctionalInterface
public interface ToolPlanner {

    /** The planner's next move. */
    sealed interface Step permits Call, Finish {}

    /** Propose a tool call. */
    record Call(ToolCall call) implements Step {}

    /** Stop and return the stage's outputs (still subject to the stage's gates, policies and approval). */
    record Finish(Map<String, String> outputs, String rationale) implements Step {}

    Step next(StageInput input, List<ToolSpec> tools, List<ToolResult> results) throws Exception;
}
