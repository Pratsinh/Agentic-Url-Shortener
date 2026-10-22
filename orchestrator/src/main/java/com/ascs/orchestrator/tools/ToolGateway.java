package com.ascs.orchestrator.tools;

import com.ascs.orchestrator.approval.ApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.model.ApprovalRequest;
import com.ascs.orchestrator.model.Decision;
import com.ascs.orchestrator.model.Impact;
import com.ascs.orchestrator.policy.PolicyContext;
import com.ascs.orchestrator.policy.SecretLeakPolicy;
import com.ascs.orchestrator.policy.Severity;
import com.ascs.orchestrator.policy.Violation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The only way an agent reaches an external system. A planner (LLM or script) proposes a {@link ToolCall}; the
 * gateway decides, fail closed, in this order:
 * <ol>
 *   <li>run not revoked by an earlier CRITICAL violation, and the per-run call budget not exhausted;</li>
 *   <li>the tool exists and is granted to the calling stage (least privilege);</li>
 *   <li>the arguments match the tool's spec;</li>
 *   <li>tool policies (BLOCK denies, CRITICAL denies and revokes the run's tool access);</li>
 *   <li>a {@link Risk#MUTATING} call needs a human approval (rejection or timeout denies it);</li>
 *   <li>execution with a correlation id; read-only calls are retried on transient failures, mutating calls never.</li>
 * </ol>
 * The output is truncated and withheld entirely if it appears to contain a credential, since it is about to enter
 * a model's context. Every step is audited by tool name, argument names and outcome, never argument values.
 * Thread-safe: agents call it from worker threads; a call blocks its thread while a human decides.
 */
public final class ToolGateway {

    /** Who is calling: the run, stage and attempt of the agent proposing the call. */
    public record Context(String runId, String stageId, int attempt) {}

    private static final SecretLeakPolicy SECRETS = new SecretLeakPolicy();

    private final Map<String, Tool> tools;
    private final Map<String, Set<String>> grants;
    private final List<ToolPolicy> policies;
    private final ApprovalGateway approvals;
    private final Duration approvalTimeout;
    private final AuditLog audit;
    private final int maxCallsPerRun;
    private final int readOnlyAttempts;
    private final int maxResultChars;
    private final Map<String, AtomicInteger> callsPerRun = new ConcurrentHashMap<>();
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    private ToolGateway(Builder b) {
        this.tools = Map.copyOf(b.tools);
        this.grants = Map.copyOf(b.grants);
        this.policies = List.copyOf(b.policies);
        this.approvals = b.approvals;
        this.approvalTimeout = b.approvalTimeout;
        this.audit = b.audit;
        this.maxCallsPerRun = b.maxCallsPerRun;
        this.readOnlyAttempts = b.readOnlyAttempts;
        this.maxResultChars = b.maxResultChars;
    }

    public static Builder builder(AuditLog audit, ApprovalGateway approvals) {
        return new Builder(audit, approvals);
    }

    /** The tools a stage may call, i.e. all a planner in that stage is shown. */
    public List<ToolSpec> toolsFor(String stageId) {
        return grants.getOrDefault(stageId, Set.of()).stream().sorted().map(n -> tools.get(n).spec()).toList();
    }

    public ToolResult invoke(Context ctx, ToolCall call) throws InterruptedException {
        int n = callsPerRun.computeIfAbsent(ctx.runId(), k -> new AtomicInteger()).incrementAndGet();
        String cid = correlationId(ctx, n);
        String where = "tool=" + call.tool() + " call=" + cid;
        log(ctx, AuditLog.Type.TOOL_CALL_REQUESTED, where + " args=" + new java.util.TreeSet<>(call.args().keySet()));

        if (revoked.contains(ctx.runId())) return deny(ctx, call, cid, "tool access revoked for this run after a CRITICAL violation");
        if (n > maxCallsPerRun) return deny(ctx, call, cid, "call budget of " + maxCallsPerRun + " per run exhausted");
        Tool tool = tools.get(call.tool());
        if (tool == null) return deny(ctx, call, cid, "unknown tool");
        if (!grants.getOrDefault(ctx.stageId(), Set.of()).contains(call.tool())) {
            return deny(ctx, call, cid, "tool not granted to stage " + ctx.stageId());
        }
        String invalid = tool.spec().validate(call.args());
        if (invalid != null) return deny(ctx, call, cid, invalid);

        for (ToolPolicy p : policies) {
            for (Violation v : p.evaluate(tool.spec(), call)) {
                log(ctx, AuditLog.Type.POLICY_VIOLATION, where + " " + v.policyId() + " " + v.severity() + ": " + v.message());
                if (v.severity() == Severity.CRITICAL) {
                    revoked.add(ctx.runId());
                    return deny(ctx, call, cid, "CRITICAL policy violation: " + v.message());
                }
                if (v.severity() == Severity.BLOCK) return deny(ctx, call, cid, "blocked by policy: " + v.message());
            }
        }
        if (tool.spec().risk() == Risk.MUTATING) {
            String refusal = approve(ctx, call, where);
            if (refusal != null) return deny(ctx, call, cid, refusal);
        }
        return execute(ctx, tool, call, cid, where);
    }

    /** null when a human approved the call, otherwise why it is refused. */
    private String approve(Context ctx, ToolCall call, String where) throws InterruptedException {
        log(ctx, AuditLog.Type.APPROVAL_REQUESTED, where);
        AtomicReference<Decision> decision = new AtomicReference<>();
        CountDownLatch decided = new CountDownLatch(1);
        try {
            approvals.request(new ApprovalRequest(ctx.runId(), ctx.stageId(), Impact.HIGH,
                    "An agent requests a MUTATING tool call: " + call.tool(), Map.of("tool-call", call.render()),
                    List.of("changes the target system; it is not retried automatically")), d -> {
                if (decision.compareAndSet(null, d)) decided.countDown();
            });
        } catch (RuntimeException e) {
            log(ctx, AuditLog.Type.APPROVAL_REJECTED, where + " approval gateway failed: " + e.getClass().getSimpleName());
            return "approval could not be requested";
        }
        if (!decided.await(approvalTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            log(ctx, AuditLog.Type.APPROVAL_TIMED_OUT, where + " no decision within " + approvalTimeout.toMillis() + " ms");
            return "no human approval within " + approvalTimeout.toMillis() + " ms";
        }
        Decision d = decision.get();
        log(ctx, d.approved() ? AuditLog.Type.APPROVAL_GRANTED : AuditLog.Type.APPROVAL_REJECTED,
                where + " approver=" + d.approver() + " comment=" + d.comment());
        return d.approved() ? null : "rejected by " + d.approver() + ": " + d.comment();
    }

    private ToolResult execute(Context ctx, Tool tool, ToolCall call, String cid, String where) throws InterruptedException {
        int attempts = tool.spec().risk() == Risk.READ_ONLY ? readOnlyAttempts : 1;
        for (int attempt = 1; ; attempt++) {
            long start = System.nanoTime();
            String transientFailure;
            try {
                ToolOutcome o = tool.invoke(call.args(), cid);
                if (o.status() < 500) return succeed(ctx, call, cid, where, o, (System.nanoTime() - start) / 1_000_000);
                transientFailure = "status " + o.status();
            } catch (ToolUnavailableException e) {
                transientFailure = "unavailable: " + e.getMessage();
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                return fail(ctx, call, cid, where, "error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (attempt >= attempts) {
                return fail(ctx, call, cid, where, transientFailure
                        + (tool.spec().risk() == Risk.MUTATING ? " (mutating calls are not retried)" : " after " + attempt + " attempt(s)"));
            }
            log(ctx, AuditLog.Type.TOOL_CALL_RETRY, where + " attempt=" + attempt + " " + transientFailure);
        }
    }

    private ToolResult succeed(Context ctx, ToolCall call, String cid, String where, ToolOutcome o, long ms) {
        String body = o.body() == null ? "" : o.body();
        String note = "";
        if (!SECRETS.evaluate(new PolicyContext(null, Map.of("tool output", body), null)).isEmpty()) {
            body = "[withheld: the tool output appears to contain a credential]";
            note = " output withheld (possible credential)";
        } else if (body.length() > maxResultChars) {
            body = body.substring(0, maxResultChars) + "...[truncated]";
            note = " output truncated to " + maxResultChars + " chars";
        }
        log(ctx, AuditLog.Type.TOOL_CALL_SUCCEEDED, where + " status=" + o.status() + " ms=" + ms + note);
        return ToolResult.ok(call.tool(), cid, o.status(), body);
    }

    private ToolResult fail(Context ctx, ToolCall call, String cid, String where, String reason) {
        log(ctx, AuditLog.Type.TOOL_CALL_FAILED, where + " " + reason);
        return ToolResult.failed(call.tool(), cid, reason);
    }

    private ToolResult deny(Context ctx, ToolCall call, String cid, String reason) {
        log(ctx, AuditLog.Type.TOOL_CALL_DENIED, "tool=" + call.tool() + " call=" + cid + " " + reason);
        return ToolResult.denied(call.tool(), cid, reason);
    }

    private void log(Context ctx, AuditLog.Type type, String detail) {
        audit.append(ctx.runId(), ctx.stageId(), type, detail);
    }

    /** {@code run.stage.a<attempt>.c<n>}, restricted to what the target accepts as a request id ([A-Za-z0-9._-]{1,64}). */
    static String correlationId(Context ctx, int n) {
        String id = (ctx.runId() + "." + ctx.stageId() + ".a" + ctx.attempt() + ".c" + n).replaceAll("[^A-Za-z0-9._-]", "-");
        return id.length() <= 64 ? id : id.substring(id.length() - 64);
    }

    /** Configures tools, per-stage grants, policies and limits. */
    public static final class Builder {
        private final AuditLog audit;
        private final ApprovalGateway approvals;
        private final Map<String, Tool> tools = new LinkedHashMap<>();
        private final Map<String, Set<String>> grants = new HashMap<>();
        private final List<ToolPolicy> policies = new ArrayList<>(List.of(ToolPolicy.of(SECRETS)));
        private Duration approvalTimeout = Duration.ofMinutes(5);
        private int maxCallsPerRun = 50;
        private int readOnlyAttempts = 2;
        private int maxResultChars = 8_000;

        private Builder(AuditLog audit, ApprovalGateway approvals) {
            this.audit = audit;
            this.approvals = approvals;
        }

        public Builder tool(Tool tool) {
            if (tools.putIfAbsent(tool.spec().name(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool " + tool.spec().name());
            }
            return this;
        }

        /** Grants a stage the named tools; unknown names are refused here rather than at call time. */
        public Builder grant(String stageId, String... toolNames) {
            for (String name : toolNames) {
                if (!tools.containsKey(name)) throw new IllegalArgumentException("cannot grant unknown tool " + name);
            }
            grants.computeIfAbsent(stageId, k -> new java.util.TreeSet<>()).addAll(List.of(toolNames));
            return this;
        }

        /** Adds a policy; the secret-leak check on arguments is always on. */
        public Builder policy(ToolPolicy policy) {
            policies.add(policy);
            return this;
        }

        public Builder approvalTimeout(Duration timeout) {
            this.approvalTimeout = timeout;
            return this;
        }

        public Builder maxCallsPerRun(int max) {
            this.maxCallsPerRun = max;
            return this;
        }

        /** Attempts for a read-only call on transient failures (mutating calls always get exactly one). */
        public Builder readOnlyAttempts(int attempts) {
            if (attempts < 1) throw new IllegalArgumentException("attempts must be >= 1");
            this.readOnlyAttempts = attempts;
            return this;
        }

        public Builder maxResultChars(int max) {
            this.maxResultChars = max;
            return this;
        }

        public ToolGateway build() {
            grants.replaceAll((stage, names) -> Set.copyOf(names));
            return new ToolGateway(this);
        }
    }
}
