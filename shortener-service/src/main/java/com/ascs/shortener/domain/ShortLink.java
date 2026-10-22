package com.ascs.shortener.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable short-link aggregate. {@code expiresAt == null} means the link never expires. */
public record ShortLink(String code, String originalUrl, Instant createdAt, Instant expiresAt) {

    public ShortLink {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(originalUrl, "originalUrl");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
