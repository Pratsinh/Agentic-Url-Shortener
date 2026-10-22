package com.ascs.shortener.infra;

import com.ascs.shortener.app.AuditTrail;
import com.ascs.shortener.domain.AuditEvent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory audit trail: keeps the newest {@code capacity} events so a redirect flood cannot exhaust
 * the heap. Sequence numbers keep increasing, so evicted ranges remain visible as gaps.
 */
public final class InMemoryAuditTrail implements AuditTrail {

    private final int capacity;
    private final Deque<AuditEvent> events = new ArrayDeque<>();
    private long nextSeq;

    public InMemoryAuditTrail(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
    }

    @Override
    public synchronized AuditEvent record(Instant at, AuditEvent.Action action, String code, String detail) {
        AuditEvent e = new AuditEvent(nextSeq++, at, action, code, detail);
        if (events.size() == capacity) events.removeFirst();
        events.addLast(e);
        return e;
    }

    @Override
    public synchronized List<AuditEvent> recent(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        List<AuditEvent> all = List.copyOf(events);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    @Override
    public synchronized List<AuditEvent> forCode(String code) {
        return events.stream().filter(e -> code.equals(e.code())).toList();
    }
}
