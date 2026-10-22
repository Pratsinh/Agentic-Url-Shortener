package com.ascs.shortener.app;

import com.ascs.shortener.domain.AuditEvent;

import java.time.Instant;
import java.util.List;

/** Audit port: append-only record of every create and redirect, successful or rejected. Must be thread-safe. */
public interface AuditTrail {

    /** Appends an event; the implementation assigns its sequence number. */
    AuditEvent record(Instant at, AuditEvent.Action action, String code, String detail);

    /** Most recent events, oldest first, at most {@code limit}. */
    List<AuditEvent> recent(int limit);

    /** All retained events for one link, oldest first. */
    List<AuditEvent> forCode(String code);
}
