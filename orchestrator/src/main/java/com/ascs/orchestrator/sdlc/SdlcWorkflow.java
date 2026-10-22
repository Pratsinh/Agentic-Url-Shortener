package com.ascs.orchestrator.sdlc;

import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.Gate;
import com.ascs.orchestrator.model.StageDefinition;

import java.util.List;

/**
 * The standard SDLC graph:
 *
 * <pre>
 * requirements -> design -> implementation -+-> testing -----------+
 *                                           +-> security-review ---+-> release-readiness
 *                                           +-> documentation -----+
 * </pre>
 */
public final class SdlcWorkflow {
    private SdlcWorkflow() {}

    public static WorkflowGraph standardGraph() {
        return new WorkflowGraph(List.of(
                StageDefinition.builder("requirements")
                        .describe("Interpret intent, surface ambiguity, normalize into an engineering problem")
                        .exitGate(Gate.requireOutputs("requirements.md")).build(),
                StageDefinition.builder("design")
                        .describe("Architecture, API and schema design")
                        .dependsOn("requirements")
                        .entryGate(Gate.requireArtifacts("requirements.md"))
                        .exitGate(Gate.requireOutputs("design.md")).build(),
                StageDefinition.builder("implementation")
                        .describe("Produce code changes")
                        .dependsOn("design")
                        .entryGate(Gate.requireArtifacts("design.md"))
                        .exitGate(Gate.requireOutputs("code.patch")).build(),
                StageDefinition.builder("testing")
                        .describe("Unit and integration tests, test report")
                        .dependsOn("implementation")
                        .entryGate(Gate.requireArtifacts("code.patch"))
                        .exitGate(Gate.requireOutputs("test-report.md")).build(),
                StageDefinition.builder("security-review")
                        .describe("Security and policy review of the change")
                        .dependsOn("implementation")
                        .entryGate(Gate.requireArtifacts("code.patch"))
                        .exitGate(Gate.requireOutputs("security-report.md")).build(),
                StageDefinition.builder("documentation")
                        .describe("API docs, README, runbook")
                        .dependsOn("implementation")
                        .entryGate(Gate.requireArtifacts("code.patch"))
                        .exitGate(Gate.requireOutputs("docs.md")).build(),
                StageDefinition.builder("release-readiness")
                        .describe("Join point: verify tests, security, docs before release")
                        .dependsOn("testing", "security-review", "documentation")
                        .entryGate(Gate.requireArtifacts("test-report.md", "security-report.md", "docs.md"))
                        .exitGate(Gate.requireOutputs("release-checklist.md")).build()));
    }
}
