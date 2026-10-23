package com.ascs.agentic.model;

import java.util.List;

/**
 * Typed results of the LLM agents. Each has a JSON Schema in {@code src/main/resources/schemas}; the model must
 * submit a document that passes the schema and the agent's semantic checks before it is bound to these types.
 */
public final class Models {
    private Models() {}

    /** Requirement understanding: intent, classification, acceptance criteria, ambiguity and codebase impact. */
    public record RequirementAnalysis(String kind, String title, String problemStatement,
                                      List<Criterion> acceptanceCriteria, List<Ambiguity> ambiguities,
                                      List<ImpactedArea> impactedAreas, List<RiskItem> risks, List<String> outOfScope) {}

    public record Criterion(String id, String text) {}

    /** {@code blocking}: work cannot sensibly proceed without a human decision on it. */
    public record Ambiguity(String id, String question, String whyItMatters, String proposedAssumption, boolean blocking) {}

    /** {@code changeType}: NEW (file to create), MODIFY (existing file to change) or CONTEXT (read to understand). */
    public record ImpactedArea(String path, String changeType, String reason) {}

    public record RiskItem(String description, String mitigation) {}

    /** How each ambiguity was resolved, by whom: the explicitly approved assumptions. */
    public record Clarifications(List<Resolution> resolutions, String respondedBy, String channel) {}

    /** {@code decision}: ANSWERED (the human supplied the answer) or ASSUMPTION_APPROVED. */
    public record Resolution(String id, String question, String decision, String text) {}

    /** Design and task DAG. {@code orchestratorAdjustments} records edges the orchestrator added (not the model). */
    public record Plan(String summary, String design, List<PlanTask> tasks, String testStrategy, String compatibility,
                       String rollback, List<String> orchestratorAdjustments) {
        public Plan withAdjustments(List<PlanTask> adjustedTasks, List<String> adjustments) {
            return new Plan(summary, design, adjustedTasks, testStrategy, compatibility, rollback, List.copyOf(adjustments));
        }
    }

    public record PlanTask(String id, String title, String description, String module, List<String> files,
                           List<String> dependsOn, List<String> acceptanceCriteria) {
        public PlanTask withDependsOn(List<String> deps) {
            return new PlanTask(id, title, description, module, files, List.copyOf(deps), acceptanceCriteria);
        }
    }

    public record ImplementationResult(String summary, List<String> filesChanged, boolean testsPassedLocally, String notes) {}

    /** Independent review of the change; {@code verdict} APPROVE or REQUEST_CHANGES. */
    public record Review(String verdict, String summary, List<Finding> findings, List<CriterionCheck> criteria) {}

    /** {@code severity}: BLOCKER, MAJOR, MINOR or INFO. */
    public record Finding(String severity, String file, int line, String title, String detail, String recommendation) {}

    public record CriterionCheck(String id, boolean met, String evidence) {}

    public record DocsResult(String summary, List<String> filesWritten) {}
}
