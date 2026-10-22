package com.ascs.orchestrator.agents;

import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.StageInput;

import java.util.Map;
import java.util.Optional;

/**
 * Join point of the delivery pipeline: summarises the review verdict and QA results, cites the exact artifact
 * versions it relied on (checked by the required-evidence policy) and recommends a decision. It cannot release
 * anything itself: the stage is HIGH impact, so its output is committed only after a human approves it.
 */
public final class ReleaseReadinessAgent implements Agent {

    public static final String OUTPUT = "release-readiness.md";

    @Override
    public AgentResult execute(StageInput input) {
        Optional<Artifact> review = input.artifact(CodeReviewAgent.OUTPUT);
        Optional<Artifact> qa = input.artifact(QaAgent.OUTPUT);
        boolean reviewApproved = review.map(a -> a.content().contains("**APPROVED**")).orElse(false);
        boolean qaMet = qa.map(a -> !a.content().contains("| **no** |") && !a.content().contains("| no |")).orElse(false);
        String decision = !reviewApproved ? "NOT READY" : qaMet ? "READY" : "READY WITH DOCUMENTED GAPS";

        StringBuilder md = new StringBuilder("# Release readiness\n\n")
                .append("- **Recommendation:** ").append(decision).append('\n')
                .append("- **Code review:** ").append(reviewApproved ? "approved" : "not approved")
                .append(" (evidence: ").append(review.map(a -> a.ref().toString()).orElse("missing")).append(")\n")
                .append("- **QA targets:** ").append(qaMet ? "all met" : "not all met, see the gap list")
                .append(" (evidence: ").append(qa.map(a -> a.ref().toString()).orElse("missing")).append(")\n\n");
        qa.ifPresent(a -> {
            String c = a.content();
            int from = c.indexOf("## 1. Summary");
            int to = c.indexOf("## 2.");
            if (from >= 0 && to > from) md.append(c, from, to);
        });
        md.append("A human release manager must approve this recommendation before it is committed.\n");
        return new AgentResult(Map.of(OUTPUT, md.toString()), "recommendation: " + decision);
    }
}
