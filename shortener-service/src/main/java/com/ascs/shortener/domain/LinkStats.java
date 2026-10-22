package com.ascs.shortener.domain;

import java.time.Instant;

/** Read model for analytics. {@code lastAccessedAt} is null until the first successful redirect. */
public record LinkStats(String code, String originalUrl, long totalClicks,
                        Instant createdAt, Instant expiresAt, Instant lastAccessedAt) {
}
