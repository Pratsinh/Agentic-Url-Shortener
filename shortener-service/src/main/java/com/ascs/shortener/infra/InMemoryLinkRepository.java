package com.ascs.shortener.infra;

import com.ascs.shortener.app.LinkRepository;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe in-memory adapter. Swappable for a JDBC adapter behind {@link LinkRepository}. */
public class InMemoryLinkRepository implements LinkRepository {

    private static final class Entry {
        final ShortLink link;
        final LongAdder clicks = new LongAdder();
        /** Monotonic: concurrent clicks may arrive out of order, the newest timestamp must win. */
        final AtomicReference<Instant> lastAccessedAt = new AtomicReference<>();

        Entry(ShortLink link) { this.link = link; }
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(ShortLink link) {
        return entries.putIfAbsent(link.code(), new Entry(link)) == null;
    }

    @Override
    public Optional<ShortLink> find(String code) {
        return Optional.ofNullable(entries.get(code)).map(e -> e.link);
    }

    @Override
    public void recordClick(String code, Instant at) {
        Entry e = entries.get(code);
        if (e == null) return;
        e.clicks.increment();
        e.lastAccessedAt.accumulateAndGet(at, (cur, next) -> cur == null || next.isAfter(cur) ? next : cur);
    }

    @Override
    public Optional<LinkStats> stats(String code) {
        return Optional.ofNullable(entries.get(code)).map(e -> new LinkStats(
                e.link.code(), e.link.originalUrl(), e.clicks.sum(),
                e.link.createdAt(), e.link.expiresAt(), e.lastAccessedAt.get()));
    }
}
