package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.agentic.model.Models;
import com.ascs.agentic.tools.PathGuard;
import com.ascs.agentic.tools.WorkspaceTools;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;
import com.ascs.orchestrator.tools.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * LLM agent: implements one approved task by editing files in the run's worktree through the gateway's tools.
 * Claims are checked against the repository, not trusted: every file the model says it changed must actually
 * differ from the base commit, a task that changed nothing fails, and "tests passed" is cross-checked with the
 * last real {@code run_tests} result. The task's diff is captured as an artifact.
 */
public final class ImplementerAgent implements Agent {

    public static final String ROLE = "implementer";

    private final AgentKit kit;
    private final DeliveryContext ctx;
    private final String taskId;

    public ImplementerAgent(AgentKit kit, DeliveryContext ctx, String taskId) {
        this.kit = kit;
        this.ctx = ctx;
        this.taskId = taskId;
    }

    public static String stageId(String taskId) {
        return "impl-" + taskId;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        Models.PlanTask task = ctx.task(taskId);
        String prompt = "You are implementing task " + task.id() + " of an approved plan.\n\n## Task\n**" + task.title() + "** (module "
                + task.module() + ")\n" + task.description() + "\n\nFiles: " + task.files() + "\nCriteria: " + task.acceptanceCriteria()
                + "\n\n## Acceptance criteria\n" + ctx.criteriaText() + "\n## Design (approved)\n" + ctx.plan().design()
                + "\n\n## Test strategy\n" + ctx.plan().testStrategy() + "\n\n" + ctx.guidance()
                + "\n\n## Your write scope\n" + ctx.scopeOf(task).describe()
                + "\n\nOther tasks of the plan (do not implement them): " + otherTasks(task) + "\n\n" + kit.conventions();
        AgentLoop.Outcome<Models.ImplementationResult> out = kit.loop().run(in, ROLE, kit.config().model(), kit.config().effort(),
                kit.config().maxTokens(), AgentKit.systemPrompt(ROLE), prompt, terminal());
        return result(in, task, out, "implementer");
    }

    AgentLoop.Terminal<Models.ImplementationResult> terminal() {
        return new AgentLoop.Terminal<>("submit_implementation", "Submit what you implemented and how you verified it.",
                Json.resource("schemas/implementation-result.schema.json"), Models.ImplementationResult.class, this::checkClaims);
    }

    /** Every claimed file must really differ from the base commit. */
    List<String> checkClaims(Models.ImplementationResult r) {
        List<String> errors = new ArrayList<>();
        Set<String> changed;
        try {
            changed = new TreeSet<>(kit.workspace().changedFiles());
        } catch (Exception e) {
            return List.of("cannot inspect the workspace: " + e.getMessage());
        }
        if (r.filesChanged().isEmpty()) errors.add("filesChanged is empty: implement the task before submitting");
        for (String f : r.filesChanged()) {
            String p;
            try {
                p = PathGuard.normalize(f);
            } catch (IllegalArgumentException e) {
                errors.add("invalid path " + f);
                continue;
            }
            if (!changed.contains(p)) errors.add("you claim to have changed '" + f + "' but it does not differ from the base commit");
        }
        return errors;
    }

    AgentResult result(StageInput in, Models.PlanTask task, AgentLoop.Outcome<Models.ImplementationResult> out, String who)
            throws Exception {
        String lastTests = lastTestRun(out.toolResults());
        boolean claimSupported = !out.value().testsPassedLocally() || lastTests.startsWith("PASSED");
        List<String> files = new ArrayList<>(out.value().filesChanged().stream().map(PathGuard::normalize).toList());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("task", task.id());
        report.put("agent", who);
        report.put("result", out.value());
        report.put("verifiedChangedFiles", files);
        report.put("lastRunTests", lastTests.isEmpty() ? "(run_tests not called)" : lastTests.lines().findFirst().orElse(""));
        report.put("testsClaimSupportedByEvidence", claimSupported);
        report.put("modelTurns", out.turns());
        report.put("toolCalls", out.toolResults().size());
        String diff = kit.workspace().diff();
        String name = in.stageId();
        return new AgentResult(Map.of(name + ".json", Json.pretty(report), name + ".patch", diff.isBlank() ? "(empty)" : diff),
                task.id() + " " + who + ": changed " + files + "; tests claim " + (claimSupported ? "supported" : "NOT supported")
                        + " by the last run_tests result; turns=" + out.turns());
    }

    static String lastTestRun(List<ToolResult> results) {
        for (int i = results.size() - 1; i >= 0; i--) {
            ToolResult r = results.get(i);
            if (r.tool().equals(WorkspaceTools.TEST) && r.status() == ToolResult.Status.OK) return r.content();
        }
        return "";
    }

    private String otherTasks(Models.PlanTask task) {
        StringBuilder sb = new StringBuilder();
        ctx.plan().tasks().stream().filter(t -> !t.id().equals(task.id()))
                .forEach(t -> sb.append(t.id()).append(" ").append(t.title()).append(" ").append(t.files()).append("; "));
        return sb.isEmpty() ? "none" : sb.toString();
    }
}
