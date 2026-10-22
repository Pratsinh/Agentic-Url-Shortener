package com.ascs.shortener.app;

import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;

import java.time.Instant;
import java.util.Optional;

/** Persistence port. Implementations must be thread-safe. */
public interface LinkRepository {

    /** Atomically stores the link; returns false (and stores nothing) if the code is taken. */
    boolean saveIfAbsent(ShortLink link);

    Optional<ShortLink> find(String code);

    /** Atomically increments the click counter and updates last-access time. */
    void recordClick(String code, Instant at);

    Optional<LinkStats> stats(String code);
}
