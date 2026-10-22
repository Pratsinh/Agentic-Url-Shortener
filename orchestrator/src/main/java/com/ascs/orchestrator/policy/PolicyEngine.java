package com.ascs.orchestrator.policy;

import java.util.ArrayList;
import java.util.List;

/** Evaluates all policies. A policy that throws fails closed (BLOCK). */
public final class PolicyEngine {

    private final List<Policy> policies;

    private PolicyEngine(List<Policy> policies) {
        this.policies = List.copyOf(policies);
    }

    public static PolicyEngine none() { return new PolicyEngine(List.of()); }

    public static PolicyEngine of(Policy... policies) { return new PolicyEngine(List.of(policies)); }

    public List<Violation> evaluate(PolicyContext ctx) {
        List<Violation> out = new ArrayList<>();
        for (Policy p : policies) {
            try {
                out.addAll(p.evaluate(ctx));
            } catch (RuntimeException e) {
                out.add(new Violation(p.id(), p.category(), Severity.BLOCK, "policy threw " + e.getClass().getSimpleName()));
            }
        }
        return out;
    }

    public static boolean has(List<Violation> vs, Severity s) {
        return vs.stream().anyMatch(v -> v.severity() == s);
    }
}
