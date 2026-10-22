package com.ascs.orchestrator.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * CHANGE_CONTROL: an agent-authored patch must not touch protected paths (CI/CD, deploy config, prod settings).
 * CRITICAL, not BLOCK: retrying an agent that tried to edit the pipeline is the wrong response; stop and escalate.
 */
public final class ProtectedPathPolicy implements Policy {

    public static final List<String> DEFAULT_PROTECTED =
            List.of(".github/", "deploy/", "application-prod", "Jenkinsfile", ".gitlab-ci");

    private final List<String> protectedFragments;

    public ProtectedPathPolicy(List<String> protectedFragments) {
        this.protectedFragments = List.copyOf(protectedFragments);
    }

    public ProtectedPathPolicy() {
        this(DEFAULT_PROTECTED);
    }

    @Override public String id() { return "change-control.protected-paths"; }
    @Override public PolicyCategory category() { return PolicyCategory.CHANGE_CONTROL; }

    @Override
    public List<Violation> evaluate(PolicyContext ctx) {
        List<Violation> out = new ArrayList<>();
        ctx.outputs().forEach((name, content) -> {
            if (!name.endsWith(".patch")) return;
            for (String line : content.split("\n")) {
                if (!(line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("diff --git"))) continue;
                for (String fragment : protectedFragments) {
                    if (line.contains(fragment)) {
                        out.add(violation(Severity.CRITICAL,
                                "patch '" + name + "' touches protected path '" + fragment + "'"));
                        return;
                    }
                }
            }
        });
        return out;
    }
}
