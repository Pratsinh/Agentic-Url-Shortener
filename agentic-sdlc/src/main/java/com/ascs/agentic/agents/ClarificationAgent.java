package com.ascs.agentic.agents;

import com.ascs.agentic.human.HumanApprovalGateway;
import com.ascs.agentic.human.HumanChannel;
import com.ascs.agentic.json.Json;
import com.ascs.agentic.model.Models;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.core.Agent;
import com.ascs.orchestrator.model.AgentResult;
import com.ascs.orchestrator.model.StageInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic agent (no model): puts every ambiguity the analyst found to a person and records, per question,
 * either the person's answer or their explicit approval of the proposed assumption. Nothing is assumed silently:
 * every ambiguity must be addressed, a rejection stops the run, and the responder must pass separation of duties.
 * The resulting guidance artifact is binding for planning; a later "revise" from a plan reviewer appends to it.
 */
public final class ClarificationAgent implements Agent {

    public static final String JSON = "clarifications.json", GUIDANCE = "guidance.md";
    private static final Pattern LINE = Pattern.compile("^\\s*(AMB-\\d+|ALL)\\s*[:=-]\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private final AgentKit kit;

    public ClarificationAgent(AgentKit kit) {
        this.kit = kit;
    }

    @Override
    public AgentResult execute(StageInput in) throws Exception {
        Models.RequirementAnalysis a = AgentKit.upstream(in, RequirementAnalystAgent.JSON, Models.RequirementAnalysis.class);
        if (a.ambiguities().isEmpty()) {
            Models.Clarifications none = new Models.Clarifications(List.of(), "n/a", "none");
            return new AgentResult(Map.of(JSON, Json.pretty(none), GUIDANCE, Render.guidance(none)),
                    "no ambiguities: no human clarification needed");
        }
        StringBuilder body = new StringBuilder("The requirement analysis found open questions. Answer each one, or approve "
                + "the proposed assumption with 'accept'. Your answers become binding guidance for design and implementation.\n\n");
        body.append("**Requirement:** ").append(in.requirement()).append("\n\n");
        for (Models.Ambiguity m : a.ambiguities()) {
            body.append("- **").append(m.id()).append("**").append(m.blocking() ? " (blocking)" : "").append(": ").append(m.question())
                    .append("\n  - why it matters: ").append(m.whyItMatters())
                    .append("\n  - proposed assumption: ").append(m.proposedAssumption()).append('\n');
        }
        Optional<HumanChannel.Reply> reply = kit.human().ask(new HumanChannel.Question(in.runId(),
                "clarify-a" + in.attempt(), "Clarify requirement: " + a.title(), body.toString(),
                List.of(HumanChannel.Verdict.ANSWER, HumanChannel.Verdict.REJECT)));
        if (reply.isEmpty()) throw new IllegalStateException("no human response to the clarification request within the timeout");
        HumanChannel.Reply r = reply.get();
        String sod = HumanApprovalGateway.separationOfDutiesViolation(r.responder());
        kit.audit().append(in.runId(), in.stageId(), AuditLog.Type.HUMAN_INPUT, "clarification response verdict=" + r.verdict()
                + " by=" + r.responder() + " channel=" + r.channel() + (sod == null ? "" : " REFUSED: " + sod));
        if (sod != null) throw new IllegalStateException("clarification refused: " + sod);
        if (r.verdict() == HumanChannel.Verdict.REJECT) {
            throw new IllegalStateException("requirement rejected by " + r.responder() + ": " + r.text());
        }
        Models.Clarifications c = resolve(a.ambiguities(), r);
        for (Models.Resolution res : c.resolutions()) {
            kit.audit().append(in.runId(), in.stageId(), AuditLog.Type.HUMAN_INPUT, res.id() + " " + res.decision() + " by " + r.responder());
        }
        return new AgentResult(Map.of(JSON, Json.pretty(c), GUIDANCE, Render.guidance(c)),
                c.resolutions().size() + " ambiguities resolved by " + r.responder() + " ("
                        + c.resolutions().stream().filter(x -> x.decision().equals("ANSWERED")).count() + " answered, "
                        + c.resolutions().stream().filter(x -> x.decision().equals("ASSUMPTION_APPROVED")).count() + " assumptions approved)");
    }

    /** Parses 'AMB-n: accept' / 'AMB-n: answer text' / 'ALL: accept'. Every ambiguity must be addressed. */
    public static Models.Clarifications resolve(List<Models.Ambiguity> ambiguities, HumanChannel.Reply r) {
        Map<String, String> given = new LinkedHashMap<>();
        for (String line : r.text().split("\\R")) {
            Matcher m = LINE.matcher(line);
            if (m.matches()) given.put(m.group(1).toUpperCase(Locale.ROOT), m.group(2).strip());
        }
        List<Models.Resolution> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Models.Ambiguity amb : ambiguities) {
            String v = given.getOrDefault(amb.id().toUpperCase(Locale.ROOT), given.get("ALL"));
            if (v == null || v.isBlank()) {
                missing.add(amb.id());
            } else if (v.equalsIgnoreCase("accept") || v.equalsIgnoreCase("accepted")) {
                out.add(new Models.Resolution(amb.id(), amb.question(), "ASSUMPTION_APPROVED", amb.proposedAssumption()));
            } else {
                out.add(new Models.Resolution(amb.id(), amb.question(), "ANSWERED", v));
            }
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("unanswered ambiguities: " + missing + " (answer or 'accept' each one)");
        return new Models.Clarifications(out, r.responder(), r.channel());
    }
}
