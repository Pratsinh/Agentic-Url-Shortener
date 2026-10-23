package com.ascs.agentic.human;

import java.util.List;
import java.util.Optional;

/**
 * How the system reaches a person: approvals and clarification questions. Implementations block until the person
 * answers or the timeout passes (empty result: the caller fails closed). Agents never hold a channel that could
 * answer for them: responses come from outside the run's workspace and are attributed to a named responder.
 */
public interface HumanChannel {

    enum Verdict { APPROVE, REJECT, REVISE, ANSWER }

    /**
     * @param allowed verdicts the person may give for this question
     * @param body    what the person is asked to decide on (markdown)
     */
    record Question(String runId, String id, String title, String body, List<Verdict> allowed) {
        public Question {
            allowed = List.copyOf(allowed);
        }
    }

    /** @param channel where it came from (console, file path) - recorded for traceability */
    record Reply(String responder, Verdict verdict, String text, String channel) {}

    Optional<Reply> ask(Question question) throws InterruptedException;

    /** Shared instructions shown with every question. */
    static String instructions(Question q) {
        StringBuilder sb = new StringBuilder("Respond with one of: ");
        for (Verdict v : q.allowed()) sb.append(v.name().toLowerCase(java.util.Locale.ROOT)).append(' ');
        sb.append("\n- approve/reject: optional comment\n");
        if (q.allowed().contains(Verdict.REVISE)) sb.append("- revise: feedback for the agent; the stage is re-planned with it\n");
        if (q.allowed().contains(Verdict.ANSWER)) {
            sb.append("- answer: one line per question, e.g. 'AMB-1: accept' (approve the proposed assumption) or 'AMB-2: <your answer>'\n");
        }
        return sb.toString();
    }
}
