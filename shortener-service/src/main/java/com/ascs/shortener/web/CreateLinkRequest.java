package com.ascs.shortener.web;

/** @param ttlSeconds optional; omit for a non-expiring link */
public record CreateLinkRequest(String url, Long ttlSeconds) {
}
