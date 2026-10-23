package com.ascs.orchestrator.tools;

import com.ascs.orchestrator.policy.Policy;
import com.ascs.orchestrator.policy.PolicyContext;
import com.ascs.orchestrator.policy.Violation;

import java.util.List;

/**
 * Policy-as-code over a proposed tool call. Severity semantics match stage policies: BLOCK denies the call,
 * CRITICAL denies it and revokes tool access for the rest of the run; INFO and WARN are audited only.
 */
@FunctionalInterface
public interface ToolPolicy {

    List<Violation> evaluate(ToolSpec tool, ToolCall call);

    /**
     * Context-aware variant the gateway calls: override it for least-privilege rules that depend on who is calling
     * (e.g. a stage may only write the files its approved task lists). Defaults to the context-free check.
     */
    default List<Violation> evaluate(ToolGateway.Context ctx, ToolSpec tool, ToolCall call) {
        return evaluate(tool, call);
    }

    /** Applies a stage-output policy (e.g. {@code SecretLeakPolicy}) to the arguments, each checked like an output. */
    static ToolPolicy of(Policy policy) {
        return (tool, call) -> policy.evaluate(new PolicyContext(null, call.args(), null));
    }
}
