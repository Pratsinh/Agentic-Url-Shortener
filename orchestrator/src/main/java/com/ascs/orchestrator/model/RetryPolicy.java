package com.ascs.orchestrator.model;

import java.time.Duration;
import java.util.Objects;

/** Bounded retries: at most {@code maxAttempts} executions, with linear backoff (attempt-1) * backoff. */
public record RetryPolicy(int maxAttempts, Duration backoff) {
    public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("maxAttempts must be 1..10");
        Objects.requireNonNull(backoff);
        if (backoff.isNegative() || backoff.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("backoff must be 0..5 minutes");
        }
    }

    public static RetryPolicy of(int maxAttempts, Duration backoff) {
        return new RetryPolicy(maxAttempts, backoff);
    }
}
