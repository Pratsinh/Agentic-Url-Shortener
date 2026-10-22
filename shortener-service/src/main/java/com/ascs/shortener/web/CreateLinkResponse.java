package com.ascs.shortener.web;

import java.time.Instant;

/** Response body of a successful create: the code, full short URL and expiry (null = never). */
public record CreateLinkResponse(String code, String shortUrl, String originalUrl,
                                 Instant createdAt, Instant expiresAt) {
}
