package com.ascs.agentic.pipeline;

import com.ascs.agentic.agents.ClarificationAgent;
import com.ascs.agentic.agents.CommitAgent;
import com.ascs.agentic.agents.ImplementerAgent;
import com.ascs.agentic.agents.PlannerAgent;
import com.ascs.agentic.agents.PullRequestAgent;
import com.ascs.agentic.agents.ReleaseReadinessAgent;
import com.ascs.agentic.agents.RequirementAnalystAgent;
import com.ascs.agentic.agents.ReviewerAgent;
import com.ascs.agentic.agents.SecurityScanAgent;
import com.ascs.agentic.agents.VerificationAgent;
import com.ascs.agentic.model.Models;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.FailureStrategy;
import com.ascs.orchestrator.model.Gate;
import com.ascs.orchestrator.model.GateResult;
import com.ascs.orchestrator.model.Impact;
import com.ascs.orchestrator.model.RetryPolicy;
import com.ascs.orchestrator.model.RollbackAction;
import com.ascs.orchestrator.model.StageDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The two workflow graphs. Discovery is fixed; delivery is compiled from the human-approved plan, so its shape
 * (parallel task stages and their ordering) is decided by the plan, while every governance control around it
 * (gates, retries, fallbacks, approval, rollback, failure strategy) is decided here, never by a model.
 *
 * <pre>
 * discovery:  analyze -> clarify -> plan[approval]
 * delivery:   baseline -> impl-T* (plan DAG, parallel where independent) -> verify(fallback repair, rollback on failure)
 *             -> { review(fallback review-fix) || docs } -> security -> release[HIGH, approval] -> commit -> pull-request (human review and merge on GitHub)
 * </pre>
 */
public final class Graphs {

    public static final List<String> DISCOVERY = List.of("analyze", "clarify", "plan");

    private Graphs() {}

    public static WorkflowGraph discovery() {
        return new WorkflowGraph(List.of(
                StageDefinition.builder("analyze").describe("Requirement understanding and codebase impact (LLM)")
                        .retry(RetryPolicy.of(2, Duration.ofSeconds(10)))
                        .exitGate(Gate.requireOutputs(RequirementAnalystAgent.JSON, RequirementAnalystAgent.MD)).build(),
                StageDefinition.builder("clarify").dependsOn("analyze").describe("Human clarification of ambiguities")
                        .retry(RetryPolicy.of(2, Duration.ZERO)).impact(Impact.MEDIUM).onFailure(FailureStrategy.SAFE_STOP)
                        .exitGate(Gate.requireOutputs(ClarificationAgent.JSON, ClarificationAgent.GUIDANCE)).build(),
                StageDefinition.builder("plan").dependsOn("analyze", "clarify").describe("Design and task DAG (LLM) for human approval")
                        .retry(RetryPolicy.of(2, Duration.ofSeconds(10))).impact(Impact.MEDIUM).requireApproval()
                        .exitGate(Gate.requireOutputs(PlannerAgent.JSON, PlannerAgent.MD)).build()));
    }

    public static WorkflowGraph delivery(Models.Plan plan, RollbackAction resetWorkspace) {
        List<StageDefinition> stages = new ArrayList<>();
        stages.add(StageDefinition.builder("baseline").describe("Verify the approved plan's integrity and run the baseline tests")
                .retry(RetryPolicy.of(2, Duration.ofSeconds(5))).rollback(resetWorkspace).onFailure(FailureStrategy.SAFE_STOP)
                .exitGate(Gate.requireOutputs(VerificationAgent.BASELINE_JSON, VerificationAgent.BASELINE_TESTS)).build());
        List<String> implStages = new ArrayList<>();
        for (Models.PlanTask t : plan.tasks()) {
            String id = ImplementerAgent.stageId(t.id());
            implStages.add(id);
            List<String> deps = new ArrayList<>(List.of("baseline"));
            t.dependsOn().forEach(d -> deps.add(ImplementerAgent.stageId(d)));
            stages.add(StageDefinition.builder(id).describe(t.id() + " " + t.title() + " (LLM implementer)")
                    .dependsOn(deps.toArray(String[]::new)).retry(RetryPolicy.of(2, Duration.ofSeconds(5))).impact(Impact.MEDIUM)
                    .exitGate(Gate.requireOutputs(id + ".json", id + ".patch")).build());
        }
        stages.add(StageDefinition.builder("verify").describe("Full build and test suite on the integrated change")
                .dependsOn(implStages.toArray(String[]::new)).fallback("repair").impact(Impact.MEDIUM)
                .onFailure(FailureStrategy.ROLLBACK_RUN)
                .exitGate(Gate.requireOutputs(VerificationAgent.VERIFY_JSON, VerificationAgent.PATCH)).build());
        stages.add(StageDefinition.builder("review").describe("Independent code review (LLM)").dependsOn("verify")
                .fallback("review-fix").impact(Impact.MEDIUM)
                .exitGate(gate("review-approves", ReviewerAgent.JSON, ReviewerAgent::gateFailure)).build());
        stages.add(StageDefinition.builder("docs").describe("Change documentation (LLM)").dependsOn("verify")
                .retry(RetryPolicy.of(2, Duration.ofSeconds(5))).exitGate(Gate.requireOutputs("docs.json")).build());
        stages.add(StageDefinition.builder("security").describe("Deterministic security and change-control scan")
                .dependsOn("review", "docs").onFailure(FailureStrategy.SAFE_STOP)
                .exitGate(gate("no-blocking-security-findings", SecurityScanAgent.JSON, SecurityScanAgent::gateFailure)).build());
        stages.add(StageDefinition.builder("release").describe("Release readiness: human go/no-go").dependsOn("security")
                .impact(Impact.HIGH).requireApproval().exitGate(Gate.requireOutputs(ReleaseReadinessAgent.MD)).build());
        stages.add(StageDefinition.builder("commit").describe("Commit the approved change on the run branch").dependsOn("release")
                .entryGate(Gate.requireArtifacts(ReleaseReadinessAgent.MD)).rollback(resetWorkspace).impact(Impact.MEDIUM)
                .exitGate(Gate.requireOutputs("commit.json")).build());
        // hand-off to human review: never rolls back the approved commit (default BLOCK_DESCENDANTS, no descendants)
        stages.add(StageDefinition.builder("pull-request").describe("Push the run branch and open a pull request for human review")
                .dependsOn("commit").entryGate(Gate.requireArtifacts(CommitAgent.JSON, ReleaseReadinessAgent.MD))
                .retry(RetryPolicy.of(2, Duration.ofSeconds(10))).impact(Impact.MEDIUM)
                .exitGate(Gate.requireOutputs(PullRequestAgent.JSON)).build());
        return new WorkflowGraph(stages);
    }

    /** Exit gate over one JSON output: {@code check} returns null when it passes, else the reason. */
    static Gate gate(String name, String output, Function<String, String> check) {
        return Gate.of(name, ctx -> {
            String content = ctx.outputs().get(output);
            if (content == null) return GateResult.fail("missing output " + output);
            String failure = check.apply(content);
            return failure == null ? GateResult.pass() : GateResult.fail(failure);
        });
    }
}
