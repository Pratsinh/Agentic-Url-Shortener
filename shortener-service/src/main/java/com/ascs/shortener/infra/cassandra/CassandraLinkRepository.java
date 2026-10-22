package com.ascs.shortener.infra.cassandra;

import com.ascs.shortener.app.LinkRepository;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.ShortenerException;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Durable link storage in Cassandra (schema in {@link CassandraSchema}).
 * <ul>
 *   <li>Create is a lightweight transaction ({@code IF NOT EXISTS}): of two concurrent creates with the same code
 *       exactly one is applied, which is what {@code saveIfAbsent} promises; the service retries on a collision.</li>
 *   <li>Reads use LOCAL_QUORUM so a link is visible right after it was created, on any coordinator.</li>
 *   <li>Clicks increment a counter; last access is written with the click time as the write timestamp, so
 *       out-of-order concurrent clicks cannot move it backwards.</li>
 * </ul>
 * Driver failures are reported as {@link ShortenerException.StorageUnavailable} (HTTP 503).
 * Timestamps are stored with millisecond precision (the service already truncates to milliseconds).
 */
public final class CassandraLinkRepository implements LinkRepository {

    private final CqlSession session;
    private final PreparedStatement insert, select, incrementClicks, touch, selectClicks, selectAccess;

    public CassandraLinkRepository(CqlSession session, String keyspace) {
        String ks = CassandraSchema.checkKeyspace(keyspace);
        this.session = session;
        this.insert = session.prepare("INSERT INTO " + ks + ".links (code, original_url, created_at, expires_at) VALUES (?, ?, ?, ?) IF NOT EXISTS");
        this.select = session.prepare("SELECT original_url, created_at, expires_at FROM " + ks + ".links WHERE code = ?");
        this.incrementClicks = session.prepare("UPDATE " + ks + ".link_clicks SET total = total + 1 WHERE code = ?");
        this.touch = session.prepare("UPDATE " + ks + ".link_access USING TIMESTAMP ? SET last_accessed_at = ? WHERE code = ?");
        this.selectClicks = session.prepare("SELECT total FROM " + ks + ".link_clicks WHERE code = ?");
        this.selectAccess = session.prepare("SELECT last_accessed_at FROM " + ks + ".link_access WHERE code = ?");
    }

    @Override
    public boolean saveIfAbsent(ShortLink link) {
        return guarded(() -> session.execute(insert.bind(link.code(), link.originalUrl(), link.createdAt(), link.expiresAt())
                .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)).wasApplied());
    }

    @Override
    public Optional<ShortLink> find(String code) {
        Row row = guarded(() -> session.execute(select.bind(code).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)).one());
        if (row == null) return Optional.empty();
        return Optional.of(new ShortLink(code, row.getString("original_url"), row.getInstant("created_at"), row.getInstant("expires_at")));
    }

    /** Only called by the service after a successful resolve, so the code is known to exist. */
    @Override
    public void recordClick(String code, Instant at) {
        long writeTimeMicros = ChronoUnit.MICROS.between(Instant.EPOCH, at);
        guarded(() -> {
            session.execute(incrementClicks.bind(code));
            return session.execute(touch.bind(writeTimeMicros, at, code));
        });
    }

    @Override
    public Optional<LinkStats> stats(String code) {
        Optional<ShortLink> link = find(code);
        if (link.isEmpty()) return Optional.empty();
        Row clicks = guarded(() -> session.execute(selectClicks.bind(code)).one());
        Row access = guarded(() -> session.execute(selectAccess.bind(code)).one());
        ShortLink l = link.get();
        return Optional.of(new LinkStats(code, l.originalUrl(), clicks == null ? 0 : clicks.getLong("total"),
                l.createdAt(), l.expiresAt(), access == null ? null : access.getInstant("last_accessed_at")));
    }

    /** Driver failures (unreachable nodes, timeouts, unavailable replicas) become a 503 instead of a 500. */
    private static <T> T guarded(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DriverException e) {
            throw new ShortenerException.StorageUnavailable(e);
        }
    }
}
