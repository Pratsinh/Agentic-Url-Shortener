package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic agent: the one side effect that leaves the sandbox. It runs only after the release stage was
 * approved by a human (the graph orders it after release, and its entry gate requires the committed release
 * artifact), commits the workspace on the run branch with the approver and evidence in the message, and is
 * compensated by resetting the branch to its base commit.
 */
public final class CommitAgent implements Agent {

    public static final String JSON = "commit.json";
    public static final String AUTHOR = "agentic-sdlc", EMAIL = "agentic-sdlc@noreply.local";

    private final AgentKit kit;
    private final DeliveryContext ctx;

    public CommitAgent(AgentKit kit, DeliveryContext ctx) {
        this.kit = kit;
        this.ctx = ctx;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        List<String> approvals = kit.audit().events().stream()
                .filter(e -> e.type() == AuditLog.Type.APPROVAL_GRANTED && e.runId().equals(in.runId()))
                .filter(e -> "release".equals(e.stageId()) || "plan".equals(e.stageId()))
                .map(e -> e.stageId() + ": " + e.detail()).toList();
        if (approvals.stream().noneMatch(a -> a.startsWith("release:"))) {
            throw new IllegalStateException("no recorded human approval of the release stage");
        }
        List<String> files = kit.workspace().changedFiles();
        String subject = ctx.plan().summary();
        if (!subject.matches("^(feat|fix|docs|test|refactor|perf|chore)(\\([a-z0-9-]+\\))?!?: .+")) subject = "feat: " + subject;
        StringBuilder msg = new StringBuilder(subject.length() > 72 ? subject.substring(0, 72) : subject).append("\n\n");
        msg.append(ctx.analysis().problemStatement()).append("\n\n");
        ctx.plan().tasks().forEach(t -> msg.append("- ").append(t.id()).append(": ").append(t.title()).append('\n'));
        msg.append("\nRun-Id: ").append(in.runId()).append('\n');
        msg.append("Plan: ").append(ctx.planArtifact().ref()).append(" sha256:").append(ctx.planArtifact().contentHash()).append('\n');
        approvals.forEach(a -> msg.append("Approved-by: ").append(a.replaceAll("\\s+comment=.*", "")).append('\n'));
        msg.append("Generated-by: agentic-sdlc LLM agents (model ").append(kit.config().model()).append(")\n");
        String commit = kit.workspace().commit(msg.toString(), AUTHOR, EMAIL);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", kit.workspace().branch());
        out.put("commit", commit);
        out.put("baseCommit", kit.workspace().baseCommit());
        out.put("files", files);
        out.put("approvals", approvals);
        out.put("message", msg.toString());
        kit.audit().append(in.runId(), in.stageId(), AuditLog.Type.WORKSPACE_CHANGED, "committed " + commit + " on " + kit.workspace().branch());
        return new AgentResult(Map.of(JSON, Json.pretty(out)), "committed " + commit.substring(0, 12) + " on " + kit.workspace().branch()
                + " (" + files.size() + " files)");
    }
}
