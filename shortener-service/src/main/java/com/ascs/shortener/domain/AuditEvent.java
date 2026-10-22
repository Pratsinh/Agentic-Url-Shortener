package com.ascs.shortener.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One business-level audit record. {@code code} is null when no link was involved (e.g. a rejected create).
 * {@code detail} never contains the full target URL (query strings can carry tokens): only its host.
 */
public record AuditEvent(long seq, Instant at, Action action, String code, String detail) {

    public enum Action { LINK_CREATED, LINK_CREATE_REJECTED, LINK_REDIRECTED, LINK_REDIRECT_REJECTED }

    public AuditEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(detail, "detail");
    }
}
