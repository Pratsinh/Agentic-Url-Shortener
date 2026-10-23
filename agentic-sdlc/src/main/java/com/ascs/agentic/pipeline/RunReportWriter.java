package com.ascs.agentic.pipeline;

import com.ascs.agentic.llm.LlmLedger;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.metrics.Metrics;
import com.ascs.orchestrator.model.Artifact;
import com.ascs.orchestrator.model.RunReport;
import com.ascs.orchestrator.model.StageResult;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders a run's evidence as one markdown report. Every number in it is read from the run's own records (model
 * ledger, engine reports and metrics, audit log, artifact store); nothing is written by a model.
 */
final class RunReportWriter {

    private static final Set<AuditLog.Type> DECISIONS = EnumSet.of(AuditLog.Type.HUMAN_INPUT, AuditLog.Type.APPROVAL_REQUESTED,
            AuditLog.Type.APPROVAL_GRANTED, AuditLog.Type.APPROVAL_REJECTED, AuditLog.Type.APPROVAL_TIMED_OUT);
    private static final Set<AuditLog.Type> GOVERNANCE = EnumSet.of(AuditLog.Type.POLICY_VIOLATION, AuditLog.Type.TOOL_CALL_DENIED,
            AuditLog.Type.STAGE_ATTEMPT_FAILED, AuditLog.Type.STAGE_RETRY, AuditLog.Type.FALLBACK_USED, AuditLog.Type.EXIT_GATE_DENIED,
            AuditLog.Type.ENTRY_GATE_DENIED, AuditLog.Type.CHANGE_APPLIED, AuditLog.Type.REPLAN, AuditLog.Type.STAGE_INVALIDATED,
            AuditLog.Type.ROLLBACK_STARTED, AuditLog.Type.ROLLBACK_COMPLETED, AuditLog.Type.ROLLBACK_FAILED, AuditLog.Type.HALT_REQUESTED,
            AuditLog.Type.STAGE_FAILED, AuditLog.Type.STAGE_BLOCKED, AuditLog.Type.LLM_CALL_FAILED, AuditLog.Type.WORKSPACE_CHANGED);

    private RunReportWriter() {}

    static String render(AgenticPipeline.Result r, String label, String requirement, ArtifactStore store, AuditLog audit,
                         Metrics.Snapshot metrics, LlmLedger ledger, Duration latency, List<String> persistenceErrors) {
        StringBuilder md = new StringBuilder("# Agentic SDLC run `").append(r.runId()).append("` (").append(label).append(")\n\n");
        md.append("**Status:** ").append(r.status()).append("  \n**End-to-end latency:** ").append(fmt(latency))
                .append("  \n**Branch:** `").append(r.branch()).append("`").append(r.commit() == null ? "" : "  \n**Commit:** `" + r.commit() + "`")
                .append(r.pullRequest() == null ? "" : "  \n**Pull request (human review and merge):** " + r.pullRequest())
                .append("\n\n## Requirement\n> ").append(requirement.replace("\n", "\n> ")).append("\n\n");

        LlmLedger.Snapshot llm = ledger.snapshot();
        md.append("## Model invocations (from the call ledger)\n");
        md.append(String.format(Locale.ROOT, "%d calls (%d failed) via provider(s) %s, models %s; tokens in=%d out=%d cache-read=%d cache-write=%d; "
                        + "estimated cost $%.2f%s; latency p50=%d ms p95=%d ms. Calls by role: %s%n%n",
                llm.calls(), llm.failedCalls(), llm.callsByProvider().keySet(), llm.callsByModel().keySet(), llm.usage().inputTokens(),
                llm.usage().outputTokens(), llm.usage().cacheReadTokens(), llm.usage().cacheWriteTokens(), llm.estimatedCostUsd(),
                llm.costComplete() ? "" : " (some models unpriced)", llm.p50LatencyMs(), llm.p95LatencyMs(), llm.callsByRole()));
        md.append("| # | Stage | Role | Model | Request id | In | Out | Cache read | ms | Stop | Tool calls |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (LlmLedger.CallRecord c : ledger.calls()) {
            md.append("| ").append(c.seq()).append(" | ").append(c.stageId()).append(" | ").append(c.role()).append(" | ").append(c.model())
                    .append(" | `").append(c.requestId()).append("` | ").append(c.usage().inputTokens()).append(" | ").append(c.usage().outputTokens())
                    .append(" | ").append(c.usage().cacheReadTokens()).append(" | ").append(c.latencyMs()).append(" | ")
                    .append(c.error() == null ? c.stopReason() : "error: " + c.error().replace("|", "/")).append(" | ")
                    .append(String.join(", ", c.toolCalls())).append(" |\n");
        }
        md.append("\nFull redacted transcripts: `llm/transcripts/`; ledger: `llm/calls.jsonl`.\n\n");

        phase(md, "Discovery workflow", r.discovery());
        phase(md, "Delivery workflow (compiled from the approved plan)", r.delivery());

        for (String name : List.of("requirement-analysis.md", "guidance.md", "plan.md", "verification.json", "review.md",
                "security.md", "release-readiness.md", "commit.json")) {
            store.latest(name).ifPresent(a -> md.append("## Artifact ").append(a.ref()).append(" (by `").append(a.producerStage())
                    .append("`, inputs ").append(a.inputs()).append(")\n\n").append(fence(a)).append("\n\n"));
        }

        md.append("## Human decisions\n");
        audit.events().stream().filter(e -> DECISIONS.contains(e.type()))
                .forEach(e -> md.append("- `#").append(e.seq()).append("` ").append(e.type()).append(" ").append(nz(e.stageId()))
                        .append(": ").append(e.detail()).append('\n'));
        md.append("\n## Governance and recovery events\n");
        audit.events().stream().filter(e -> GOVERNANCE.contains(e.type()))
                .forEach(e -> md.append("- `#").append(e.seq()).append("` ").append(e.type()).append(" ").append(nz(e.stageId()))
                        .append(": ").append(abbrev(e.detail())).append('\n'));
        md.append("\n## Reliability metrics (engine, both phases)\n```\n").append(metrics.format()).append("\n```\n");
        md.append("\n## Audit\n").append(audit.events().size()).append(" events, hash chain ")
                .append(audit.verify() ? "verified intact" : "**BROKEN**").append(". Persisted copy: `audit.jsonl` (re-verify with `verify-audit`).\n");
        if (!persistenceErrors.isEmpty()) md.append("\n**Persistence errors:** ").append(persistenceErrors).append('\n');
        return md.toString();
    }

    private static void phase(StringBuilder md, String title, RunReport report) {
        md.append("## ").append(title).append('\n');
        if (report == null) {
            md.append("Not run.\n\n");
            return;
        }
        md.append("Outcome **").append(report.outcome()).append("** in ").append(fmt(report.latency())).append(", re-plans: ")
                .append(report.replans()).append("\n\n| Stage | Status | Attempts | Fallback | Duration | Reason |\n|---|---|---|---|---|---|\n");
        for (StageResult s : report.stages()) {
            md.append("| ").append(s.stageId()).append(" | ").append(s.status()).append(" | ").append(s.attempts()).append(" | ")
                    .append(s.usedFallback() ? "yes" : "").append(" | ").append(fmt(s.duration())).append(" | ")
                    .append(abbrev(s.reason()).replace("|", "/")).append(" |\n");
        }
        md.append('\n');
    }

    private static String fence(Artifact a) {
        String c = a.content().length() > 12_000 ? a.content().substring(0, 12_000) + "\n...[truncated; see artifacts/]" : a.content();
        return a.name().endsWith(".md") ? c : "```json\n" + c + "\n```";
    }

    private static String fmt(Duration d) {
        long s = d.toSeconds();
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : d.toMillis() + " ms";
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    private static String abbrev(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
