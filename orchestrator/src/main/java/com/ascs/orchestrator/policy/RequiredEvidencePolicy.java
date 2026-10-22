package com.ascs.orchestrator.policy;

import java.util.ArrayList;
import java.util.List;

/** COMPLIANCE: a stage's output must cite evidence (e.g. a release checklist must reference test and security reports). */
public final class RequiredEvidencePolicy implements Policy {

    private final String stageId;
    private final String outputName;
    private final List<String> requiredFragments;

    public RequiredEvidencePolicy(String stageId, String outputName, String... requiredFragments) {
        this.stageId = stageId;
        this.outputName = outputName;
        this.requiredFragments = List.of(requiredFragments);
    }

    @Override public String id() { return "compliance.required-evidence." + stageId; }
    @Override public PolicyCategory category() { return PolicyCategory.COMPLIANCE; }

    @Override
    public List<Violation> evaluate(PolicyContext ctx) {
        List<Violation> out = new ArrayList<>();
        if (!ctx.stage().id().equals(stageId)) return out;
        String content = ctx.outputs().get(outputName);
        if (content == null) return out;
        for (String f : requiredFragments) {
            if (!content.contains(f)) out.add(violation(Severity.BLOCK, "'" + outputName + "' lacks evidence reference '" + f + "'"));
        }
        return out;
    }
}
