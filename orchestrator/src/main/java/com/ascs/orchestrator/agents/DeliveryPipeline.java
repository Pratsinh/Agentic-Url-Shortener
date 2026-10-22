package com.ascs.orchestrator.agents;

import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.Gate;
import com.ascs.orchestrator.model.Impact;
import com.ascs.orchestrator.model.StageDefinition;
import com.ascs.orchestrator.policy.PolicyEngine;
import com.ascs.orchestrator.policy.RequiredEvidencePolicy;
import com.ascs.orchestrator.policy.SecretLeakPolicy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agentic delivery workflow for this repository:
 *
 * <pre>
 * requirements -> design -> development -+-> code-review --+-> release-readiness (HIGH impact: human approval)
 *                                        +-> qa -----------+
 * </pre>
 */
public final class DeliveryPipeline {
    private DeliveryPipeline() {}

    public static final String REQUIREMENT = "Build a URL shortener with create, redirect, expiry, click analytics, "
            + "an audit trail, error handling and logging, Cassandra storage with a Redis cache, a published OpenAPI contract "
            + "and a governed tool gateway for agents, "
            + "delivered by governed AI agents";

    public static WorkflowGraph graph() {
        return new WorkflowGraph(List.of(
                StageDefinition.builder("requirements")
                        .describe("Requirements agent: user stories and acceptance criteria")
                        .exitGate(Gate.requireOutputs(RequirementsAgent.OUTPUT)).build(),
                StageDefinition.builder("design")
                        .describe("Design agent: design document, diagrams, architecture conformance")
                        .dependsOn("requirements")
                        .entryGate(Gate.requireArtifacts(RequirementsAgent.OUTPUT))
                        .exitGate(Gate.requireOutputs(DesignAgent.OUTPUT)).build(),
                StageDefinition.builder("development")
                        .describe("Development agent: commit-history audit and proposed commits")
                        .dependsOn("design")
                        .exitGate(Gate.requireOutputs(DevelopmentAgent.OUTPUT)).build(),
                StageDefinition.builder("code-review")
                        .describe("Code-review agent: every file reviewed, findings and resolutions")
                        .dependsOn("development")
                        .exitGate(Gate.requireOutputs(CodeReviewAgent.OUTPUT)).build(),
                StageDefinition.builder("qa")
                        .describe("QA agent: unit and functional coverage against the target, gaps")
                        .dependsOn("development")
                        .entryGate(Gate.requireArtifacts(RequirementsAgent.OUTPUT))
                        .exitGate(Gate.requireOutputs(QaAgent.OUTPUT)).build(),
                StageDefinition.builder("release-readiness")
                        .describe("Release gate: review verdict and QA evidence, approved by a human")
                        .dependsOn("code-review", "qa")
                        .entryGate(Gate.requireArtifacts(CodeReviewAgent.OUTPUT, QaAgent.OUTPUT))
                        .exitGate(Gate.requireOutputs(ReleaseReadinessAgent.OUTPUT))
                        .impact(Impact.HIGH).requireApproval().build()));
    }

    /** @param jacocoXmls coverage reports to merge, one per module ({@link #jacocoReports} by default) */
    public static Map<String, Agent> agents(Path projectRoot, List<Path> jacocoXmls, CommandRunner git, double coverageTarget) {
        Map<String, Agent> m = new LinkedHashMap<>();
        m.put("requirements", new RequirementsAgent());
        m.put("design", new DesignAgent(projectRoot));
        m.put("development", new DevelopmentAgent(git));
        m.put("code-review", new CodeReviewAgent(projectRoot));
        m.put("qa", new QaAgent(projectRoot, jacocoXmls, inModules(projectRoot, "target/surefire-reports"), coverageTarget));
        m.put("release-readiness", new ReleaseReadinessAgent());
        return m;
    }

    /** The JaCoCo XML report that {@code mvn verify} writes in every module. */
    public static List<Path> jacocoReports(Path projectRoot) {
        return inModules(projectRoot, "target/site/jacoco/jacoco.xml");
    }

    private static List<Path> inModules(Path projectRoot, String relative) {
        return SourceFile.modules(projectRoot).stream().map(m -> projectRoot.resolve(m).resolve(relative)).toList();
    }

    /** No agent output may contain a credential; the release summary must cite the review and QA artifacts it used. */
    public static PolicyEngine policies() {
        return PolicyEngine.of(new SecretLeakPolicy(), new RequiredEvidencePolicy("release-readiness",
                ReleaseReadinessAgent.OUTPUT, CodeReviewAgent.OUTPUT + "@", QaAgent.OUTPUT + "@"));
    }
}
