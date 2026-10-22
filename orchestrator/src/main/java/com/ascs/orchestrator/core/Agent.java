package com.ascs.orchestrator.core;

import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

/**
 * Pluggable unit of work. Implementations may be LLM-backed or deterministic; the engine treats
 * them identically and never lets them touch shared state directly: they read an immutable
 * {@link StageInput} and return outputs, which the engine validates (exit gates) before committing.
 */
@FunctionalInterface
public interface Agent {
    AgentResult execute(StageInput input) throws Exception;
}
