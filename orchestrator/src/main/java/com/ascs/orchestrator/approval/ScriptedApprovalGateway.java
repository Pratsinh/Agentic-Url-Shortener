package com.ascs.orchestrator.approval;

import com.ascs.orchestrator.model.ApprovalRequest;
import com.ascs.orchestrator.model.Decision;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** Deterministic approver for demos and tests; records every request it receives. */
public final class ScriptedApprovalGateway implements ApprovalGateway {

    private final Function<ApprovalRequest, Decision> script;
    private final List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

    /** @param script returns the decision, or null to stay silent (simulates an unresponsive human) */
    public ScriptedApprovalGateway(Function<ApprovalRequest, Decision> script) {
        this.script = script;
    }

    public static ScriptedApprovalGateway approveAll(String approver, String comment) {
        return new ScriptedApprovalGateway(r -> Decision.approve(approver, comment));
    }

    public static ScriptedApprovalGateway silent() {
        return new ScriptedApprovalGateway(r -> null);
    }

    public List<ApprovalRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void request(ApprovalRequest request, Consumer<Decision> callback) {
        requests.add(request);
        Decision d = script.apply(request);
        if (d != null) callback.accept(d);
    }
}
