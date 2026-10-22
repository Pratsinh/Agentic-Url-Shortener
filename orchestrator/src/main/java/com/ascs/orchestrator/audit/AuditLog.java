package com.ascs.orchestrator.audit;

import com.ascs.orchestrator.util.Hashing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, hash-chained audit trail. Each event commits to its predecessor's hash, so any
 * later edit, deletion, or reordering is detectable via {@link #verify()}.
 */
public final class AuditLog {

    public static final String GENESIS = "0".repeat(64);

    public enum Type {
        PLAN_CREATED, RUN_STARTED, STAGE_STARTED, STAGE_ATTEMPT_FAILED, STAGE_RETRY, FALLBACK_USED,
        ENTRY_GATE_DENIED, EXIT_GATE_DENIED, POLICY_VIOLATION, ARTIFACT_PRODUCED,
        APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REJECTED, APPROVAL_TIMED_OUT,
        STAGE_SUCCEEDED, STAGE_FAILED, STAGE_BLOCKED, STAGE_RESULT_DISCARDED,
        CHANGE_APPLIED, CHANGE_REJECTED, CHANGE_NOOP, STAGE_INVALIDATED, REPLAN,
        HALT_REQUESTED, ROLLBACK_STARTED, ROLLBACK_COMPLETED, ROLLBACK_FAILED,
        RUN_COMPLETED, RUN_ABORTED,
        TOOL_CALL_REQUESTED, TOOL_CALL_DENIED, TOOL_CALL_RETRY, TOOL_CALL_SUCCEEDED, TOOL_CALL_FAILED
    }

    public record Event(long seq, Instant at, String runId, String stageId, Type type,
                        String detail, String prevHash, String hash) {
    }

    private final List<Event> events = new ArrayList<>();
    private final Clock clock;

    public AuditLog(Clock clock) {
        this.clock = clock;
    }

    public synchronized Event append(String runId, String stageId, Type type, String detail) {
        String prev = events.isEmpty() ? GENESIS : events.get(events.size() - 1).hash();
        long seq = events.size();
        Instant at = clock.instant();
        Event e = new Event(seq, at, runId, stageId, type, detail, prev,
                hashOf(seq, at, runId, stageId, type, detail, prev));
        events.add(e);
        return e;
    }

    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    public synchronized boolean verify() {
        return verifyChain(events);
    }

    public static boolean verifyChain(List<Event> list) {
        String prev = GENESIS;
        long expectedSeq = 0;
        for (Event e : list) {
            if (e.seq() != expectedSeq || !e.prevHash().equals(prev)
                    || !e.hash().equals(hashOf(e.seq(), e.at(), e.runId(), e.stageId(), e.type(), e.detail(), e.prevHash()))) {
                return false;
            }
            prev = e.hash();
            expectedSeq++;
        }
        return true;
    }

    private static String hashOf(long seq, Instant at, String runId, String stageId, Type type,
                                 String detail, String prev) {
        return Hashing.sha256(String.join("\u0000", String.valueOf(seq), String.valueOf(at),
                String.valueOf(runId), String.valueOf(stageId), String.valueOf(type),
                String.valueOf(detail), prev));
    }
}
