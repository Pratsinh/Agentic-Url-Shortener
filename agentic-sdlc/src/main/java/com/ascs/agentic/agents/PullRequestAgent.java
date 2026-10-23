package com.ascs.agentic.agents;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.Redactor;
import com.ascs.agentic.vcs.PullRequests;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic agent (no model): hands the approved change to human review. It pushes the run branch and opens a
 * pull request whose description is the release-readiness evidence the approver saw, plus the approvals and the
 * run id. It runs only after the commit stage (entry gate) and re-checks the recorded human release approval itself.
 * It cannot merge: merging is a human decision on GitHub. When pull requests are not configured it records that
 * and succeeds, so the run still ends with the approved commit on the local run branch.
 */
public final class PullRequestAgent implements Agent {

    public static final String JSON = "pull-request.json";
    /** GitHub's limit for a pull request description is 65,536 characters. */
    static final int MAX_BODY = 60_000;

    private final AgentKit kit;
    private final PullRequests prs;
    private final Redactor redactor;

    public PullRequestAgent(AgentKit kit, PullRequests prs, Redactor redactor) {
        this.kit = kit;
        this.prs = prs;
        this.redactor = redactor;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        JsonNode commit = Json.parse(AgentKit.content(in, CommitAgent.JSON));
        String branch = commit.path("branch").asText();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", branch);
        out.put("commit", commit.path("commit").asText());
        if (!prs.enabled()) {
            out.put("status", "SKIPPED");
            out.put("reason", "pull requests not enabled (AGENTIC_PR=on and GITHUB_TOKEN); the approved commit is on the local branch");
            return new AgentResult(Map.of(JSON, Json.pretty(out)), "skipped: pull requests not enabled; review branch " + branch + " locally");
        }
        boolean releaseApproved = kit.audit().events().stream().anyMatch(e -> e.type() == AuditLog.Type.APPROVAL_GRANTED
                && e.runId().equals(in.runId()) && "release".equals(e.stageId()));
        if (!releaseApproved) throw new IllegalStateException("no recorded human approval of the release stage");

        String repo = prs.repoFor(kit.workspace().remoteUrl(prs.remote()));
        try {
            kit.workspace().push(prs.remote(), prs.token().value());
        } catch (Exception e) {
            throw new IllegalStateException("push of " + branch + " to " + prs.remote() + " failed: " + prs.redact(e.getMessage()));
        }
        kit.audit().append(in.runId(), in.stageId(), AuditLog.Type.WORKSPACE_CHANGED, "pushed " + branch + " to " + prs.remote());

        String title = commit.path("message").asText().lines().findFirst().orElse("agentic change " + in.runId());
        PullRequests.Opened pr = prs.open(repo, branch, title, body(in, commit));
        kit.audit().append(in.runId(), in.stageId(), AuditLog.Type.WORKSPACE_CHANGED, (pr.existed() ? "found open" : "opened")
                + " pull request #" + pr.number() + " " + pr.url() + " into " + prs.base() + " (merge is a human decision)");
        out.put("status", pr.existed() ? "ALREADY_OPEN" : "OPENED");
        out.put("repository", repo);
        out.put("base", prs.base());
        out.put("number", pr.number());
        out.put("url", pr.url());
        return new AgentResult(Map.of(JSON, Json.pretty(out)), "pull request #" + pr.number() + " " + pr.url() + " for human review");
    }

    /** Description: the evidence the release approver saw, then provenance. Redacted and within GitHub's limit. */
    String body(StageInput in, JsonNode commit) {
        StringBuilder sb = new StringBuilder();
        sb.append("> Opened by **agentic-sdlc** run `").append(in.runId()).append("` after a human approved the release. ")
                .append("Review it like any change: merging is your decision.\n\n");
        commit.path("approvals").forEach(a -> sb.append("- Approved: ").append(a.asText().replaceAll("\\s+comment=.*", "")).append('\n'));
        sb.append("- Commit: `").append(commit.path("commit").asText()).append("` on base `")
                .append(commit.path("baseCommit").asText()).append("`\n\n");
        sb.append(in.artifact(ReleaseReadinessAgent.MD).map(a -> a.content()).orElse("(release-readiness report missing)"));
        String body = prs.redact(redactor.redact(sb.toString()));
        return body.length() > MAX_BODY ? body.substring(0, MAX_BODY) + "\n\n... (truncated; full report in the run record)" : body;
    }
}
