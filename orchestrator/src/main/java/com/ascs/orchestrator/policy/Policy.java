package com.ascs.orchestrator.policy;

import java.util.List;

/** Policy-as-code: a pure, side-effect-free check over a stage's proposed outputs. */
public interface Policy {
    String id();

    PolicyCategory category();

    List<Violation> evaluate(PolicyContext ctx);

    default Violation violation(Severity severity, String message) {
        return new Violation(id(), category(), severity, message);
    }
}
