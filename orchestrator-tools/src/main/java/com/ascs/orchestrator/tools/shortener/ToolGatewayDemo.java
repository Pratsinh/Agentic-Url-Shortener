package com.ascs.orchestrator.tools.shortener;

import com.ascs.orchestrator.approval.ApprovalGateway;
import com.ascs.orchestrator.approval.ConsoleApprovalGateway;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.ArtifactStore;
import com.ascs.orchestrator.core.WorkflowEngine;
import com.ascs.orchestrator.core.WorkflowGraph;
import com.ascs.orchestrator.model.Decision;
import com.ascs.orchestrator.model.Gate;
import com.ascs.orchestrator.model.RunOutcome;
import com.ascs.orchestrator.model.RunReport;
import com.ascs.orchestrator.model.StageDefinition;
import com.ascs.orchestrator.tools.Tool;
import com.ascs.orchestrator.tools.ToolCall;
import com.ascs.orchestrator.tools.ToolGateway;
import com.ascs.orchestrator.tools.ToolPlanner;
import com.ascs.orchestrator.tools.ToolResult;
import com.ascs.orchestrator.tools.ToolSpec;
import com.ascs.orchestrator.tools.ToolUsingAgent;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An agent operating the running shortener through the governed tool gateway: it creates a link (a MUTATING call
 * that a human must approve), reads its stats, makes one malformed call (denied by the gateway, as an LLM's mistake
 * would be) and reads the link's audit history. The planner is a deterministic stand-in for an LLM.
 * Usage: ToolGatewayDemo [baseUrl, default http://localhost:8080] [--interactive]
 */
public final class ToolGatewayDemo {
    private ToolGatewayDemo() {}

    public static final String STAGE = "link-ops";
    public static final String OUTPUT = "link-report.md";
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"(\\w+)\"");

    /** The run, its full audit trail and the tools the planner was shown. */
    public record Result(RunReport report, List<AuditLog.Event> audit, boolean auditIntact, List<ToolSpec> tools, String output) {}

    /** Approves link creation for example.com targets only, the kind of rule a release manager states. */
    public static ApprovalGateway scriptedApprover() {
        return new ScriptedApprovalGateway(r -> r.proposedOutputs().getOrDefault("tool-call", "").contains("url=https://example.com/")
                ? Decision.approve("release-manager", "example.com test link")
                : Decision.reject("release-manager", "only example.com links may be created by agents"));
    }

    /** Stand-in for an LLM: proposes the calls in order and writes the report from the results it got back. */
    static ToolPlanner planner(String url) {
        return (input, tools, results) -> {
            if (results.isEmpty()) return call("createLink", Map.of("url", url, "ttlSeconds", "3600"));
            Matcher m = CODE.matcher(results.get(0).content());
            if (results.get(0).status() != ToolResult.Status.OK || !m.find()) {
                return new ToolPlanner.Finish(Map.of(), "link was not created: " + results.get(0).reason());
            }
            String code = m.group(1);
            return switch (results.size()) {
                case 1 -> call("getLinkStats", Map.of("code", code));
                case 2 -> call("getRecentAudit", Map.of("limit", "lots")); // malformed on purpose: the gateway must refuse it
                case 3 -> call("getLinkAudit", Map.of("code", code));
                default -> new ToolPlanner.Finish(Map.of(OUTPUT, report(code, results)), "link " + code + " created and verified");
            };
        };
    }

    private static ToolPlanner.Call call(String tool, Map<String, String> args) {
        return new ToolPlanner.Call(new ToolCall(tool, args));
    }

    private static String report(String code, List<ToolResult> results) {
        StringBuilder md = new StringBuilder("# Link operations\n\nCreated `" + code + "`.\n\n| Tool | Status | HTTP | Correlation id |\n|---|---|---|---|\n");
        for (ToolResult r : results) {
            md.append("| ").append(r.tool()).append(" | ").append(r.status()).append(" | ").append(r.httpStatus())
                    .append(" | ").append(r.correlationId()).append(" |\n");
        }
        return md.toString();
    }

    public static Result run(String baseUrl, ApprovalGateway approvals, String targetUrl) {
        AuditLog audit = new AuditLog(Clock.systemUTC());
        ToolGateway.Builder gb = ToolGateway.builder(audit, approvals).approvalTimeout(Duration.ofMinutes(2));
        for (Tool t : ShortenerTools.create(baseUrl, ShortenerContract.load(), Duration.ofSeconds(5))) {
            gb.tool(t).grant(STAGE, t.spec().name());
        }
        ToolGateway gateway = gb.build();
        WorkflowGraph graph = new WorkflowGraph(List.of(StageDefinition.builder(STAGE)
                .describe("Agent operates the shortener through the governed tool gateway")
                .exitGate(Gate.requireOutputs(OUTPUT)).build()));
        ArtifactStore store = new ArtifactStore();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            RunReport report = WorkflowEngine.builder(graph, Map.of(STAGE, new ToolUsingAgent(gateway, planner(targetUrl), 10)), pool)
                    .store(store).audit(audit).build().run("tools-demo", "Create a short link and verify it through the API");
            String output = store.latest(OUTPUT).map(a -> a.content()).orElse("");
            return new Result(report, audit.events(), audit.verify(), gateway.toolsFor(STAGE), output);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The tools as a planner sees them, the audit trail, and the stage's report. */
    static String render(Result r) {
        StringBuilder sb = new StringBuilder("Tools granted to stage " + STAGE + " (as an LLM would be shown them):\n");
        r.tools().forEach(t -> sb.append("  ").append(t.name()).append(" [").append(t.risk()).append("] ").append(t.inputSchemaJson()).append('\n'));
        sb.append("\nAudit trail:\n");
        r.audit().forEach(e -> sb.append(String.format("#%03d %-20s %-9s %s%n", e.seq(), e.type(), e.stageId() == null ? "-" : e.stageId(), e.detail())));
        sb.append("\nhash chain ").append(r.auditIntact() ? "verified intact" : "BROKEN").append("; outcome ").append(r.report().outcome());
        return sb.append("\n\n").append(r.output()).toString();
    }

    public static void main(String[] args) {
        String baseUrl = "http://localhost:8080";
        boolean interactive = false;
        for (String a : args) {
            if (a.equals("--interactive")) interactive = true;
            else baseUrl = a;
        }
        Result r = run(baseUrl, interactive ? new ConsoleApprovalGateway() : scriptedApprover(), "https://example.com/tool-gateway-demo");
        System.out.println(render(r));
        if (r.report().outcome() != RunOutcome.SUCCEEDED) {
            throw new IllegalStateException("tool gateway demo did not succeed: " + r.report().stages().get(0).reason());
        }
    }
}
