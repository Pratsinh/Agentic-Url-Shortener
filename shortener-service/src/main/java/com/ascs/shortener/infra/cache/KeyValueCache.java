package com.ascs.shortener.infra.cache;

import java.time.Duration;
import java.util.Optional;

/** Minimal string cache the caching decorator needs; implemented over Redis, faked in tests. May throw when the cache is down. */
public interface KeyValueCache {

    Optional<String> get(String key);

    /** Stores {@code value} for {@code ttl} (must be positive). */
    void set(String key, String value, Duration ttl);
}
