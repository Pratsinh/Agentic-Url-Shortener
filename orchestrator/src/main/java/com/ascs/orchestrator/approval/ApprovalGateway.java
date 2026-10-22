package com.ascs.orchestrator.approval;

import com.ascs.orchestrator.model.ApprovalRequest;
import com.ascs.orchestrator.model.Decision;

import java.util.function.Consumer;

/**
 * Human-in-the-loop port. Implementations deliver the request to a person (console, ticket, chat, UI) and
 * invoke {@code callback} exactly once when they decide, from any thread. Never calling back is legal:
 * the engine's approval timeout then rejects the stage (fail closed).
 */
@FunctionalInterface
public interface ApprovalGateway {
    void request(ApprovalRequest request, Consumer<Decision> callback);
}
