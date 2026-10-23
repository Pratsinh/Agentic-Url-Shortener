package com.ascs.agentic.human;

import com.ascs.orchestrator.approval.ApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.model.ApprovalRequest;
import com.ascs.orchestrator.model.Decision;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Bridges the engine's approval checkpoints to a {@link HumanChannel}, with separation of duties: a decision is
 * only accepted from a named person who is not one of the system's own agent identities, so no model can approve
 * its own work. A reviewer may also answer {@code revise} with feedback: the feedback becomes a new version of the
 * guidance artifact, and the engine re-plans every stage that consumed the old one (the pending approval is then
 * stale and discarded). No answer within the timeout leaves the callback uncalled and the engine's approval
 * timeout rejects the stage (fail closed).
 */
public final class HumanApprovalGateway implements ApprovalGateway {

    /** Identities that may never approve (the agents of this system and generic machine identities). */
    public static final Set<String> AGENT_IDENTITIES = Set.of("requirements-analyst", "planner", "implementer", "repair",
            "reviewer", "docs-writer", "agentic-sdlc", "agent", "llm", "model", "claude", "assistant", "system", "bot");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._@ \\-]{2,64}");
    private static final int MAX_SHOWN = 6_000;

    /** Receives a revise request: (stage id, responder, feedback); false when the stage cannot be revised. */
    public interface ReviseHandler {
        boolean revise(String stageId, String responder, String feedback);
    }

    private final HumanChannel channel;
    private final AuditLog audit;
    private final ReviseHandler reviseHandler;

    public HumanApprovalGateway(HumanChannel channel, AuditLog audit, ReviseHandler reviseHandler) {
        this.channel = channel;
        this.audit = audit;
        this.reviseHandler = reviseHandler;
    }

    /** null when {@code responder} may approve; otherwise why not. */
    public static String separationOfDutiesViolation(String responder) {
        if (responder == null || !NAME.matcher(responder).matches()) return "approver must be a named person";
        String r = responder.strip().toLowerCase(Locale.ROOT);
        for (String id : AGENT_IDENTITIES) {
            if (r.equals(id) || r.startsWith(id + "-") || r.startsWith(id + ":")) {
                return "'" + responder + "' is an agent/system identity and cannot approve";
            }
        }
        return null;
    }

    @Override
    public void request(ApprovalRequest r, Consumer<Decision> callback) {
        List<HumanChannel.Verdict> allowed = reviseHandler == null
                ? List.of(HumanChannel.Verdict.APPROVE, HumanChannel.Verdict.REJECT)
                : List.of(HumanChannel.Verdict.APPROVE, HumanChannel.Verdict.REJECT, HumanChannel.Verdict.REVISE);
        HumanChannel.Question q = new HumanChannel.Question(r.runId(), "approve-" + r.stageId() + "-" + System.currentTimeMillis(),
                "Approve stage '" + r.stageId() + "' (impact " + r.impact() + ")", render(r), allowed);
        Thread t = new Thread(() -> {
            try {
                Optional<HumanChannel.Reply> reply = channel.ask(q);
                if (reply.isEmpty()) {
                    audit.append(r.runId(), r.stageId(), AuditLog.Type.HUMAN_INPUT, "no response via channel (approval will time out)");
                    return;
                }
                HumanChannel.Reply rep = reply.get();
                String sod = separationOfDutiesViolation(rep.responder());
                audit.append(r.runId(), r.stageId(), AuditLog.Type.HUMAN_INPUT, "approval response verdict=" + rep.verdict()
                        + " by=" + rep.responder() + " channel=" + rep.channel() + (sod == null ? "" : " REFUSED: " + sod));
                if (sod != null) {
                    callback.accept(Decision.reject("policy:separation-of-duties", sod));
                } else if (rep.verdict() == HumanChannel.Verdict.APPROVE) {
                    callback.accept(Decision.approve(rep.responder(), rep.text()));
                } else if (rep.verdict() == HumanChannel.Verdict.REVISE && reviseHandler != null
                        && reviseHandler.revise(r.stageId(), rep.responder(), rep.text())) {
                    audit.append(r.runId(), r.stageId(), AuditLog.Type.HUMAN_INPUT, "revision requested by " + rep.responder()
                            + ": the stage is re-planned with the feedback; this approval request is superseded");
                } else {
                    callback.accept(Decision.reject(rep.responder(), rep.text()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                callback.accept(Decision.reject("system", "approval channel failed: " + e.getClass().getSimpleName()));
            }
        }, "approval-" + r.stageId());
        t.setDaemon(true);
        t.start();
    }

    static String render(ApprovalRequest r) {
        StringBuilder sb = new StringBuilder("**Summary:** ").append(r.summary()).append("\n\n");
        for (String w : r.warnings()) sb.append("> WARNING: ").append(w).append('\n');
        Map<String, String> outs = r.proposedOutputs();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(outs).entrySet()) {
            String name = e.getKey();
            if (name.endsWith(".json") && outs.containsKey(name.substring(0, name.length() - 5) + ".md")) continue;
            String v = e.getValue();
            sb.append("\n### ").append(name).append("\n\n").append(v.length() > MAX_SHOWN
                    ? v.substring(0, MAX_SHOWN) + "\n...[truncated; full content in the run's artifacts]" : v).append('\n');
        }
        return sb.toString();
    }
}
