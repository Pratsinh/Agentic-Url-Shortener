package com.ascs.shortener.infra.cache;

import com.ascs.shortener.app.LinkRepository;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Cache-aside decorator for the redirect hot path (design section 6):
 * <ul>
 *   <li>{@code find} reads {@code link:{code}} from the cache; on a miss it reads the store and caches the link for
 *       min(remaining life, 24 h), so an entry never outlives its link. Expiry is still checked by the service.</li>
 *   <li>Unknown codes are cached as a negative entry for 60 s, so code-guessing traffic does not reach the store.</li>
 *   <li>A successful create primes the cache (and overwrites any negative entry for that code).</li>
 *   <li>Clicks and stats always go to the store: counters must not be served stale.</li>
 *   <li>The cache is an optimisation, never a dependency: if it fails, requests read through to the store.</li>
 * </ul>
 */
public final class CachingLinkRepository implements LinkRepository {

    static final Duration MAX_TTL = Duration.ofHours(24);
    static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);
    static final String MISSING = "-";
    private static final Logger LOG = System.getLogger(CachingLinkRepository.class.getName());

    private final LinkRepository store;
    private final KeyValueCache cache;
    private final Clock clock;
    private volatile boolean degraded;

    public CachingLinkRepository(LinkRepository store, KeyValueCache cache, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.cache = Objects.requireNonNull(cache);
        this.clock = Objects.requireNonNull(clock);
    }

    static String key(String code) {
        return "link:" + code;
    }

    @Override
    public boolean saveIfAbsent(ShortLink link) {
        boolean saved = store.saveIfAbsent(link);
        if (saved) put(link);
        return saved;
    }

    @Override
    public Optional<ShortLink> find(String code) {
        Optional<String> hit = cacheGet(key(code));
        if (hit.isPresent()) return hit.get().equals(MISSING) ? Optional.empty() : Optional.of(decode(code, hit.get()));

        Optional<ShortLink> link = store.find(code);
        if (link.isPresent()) put(link.get());
        else cacheSet(key(code), MISSING, NEGATIVE_TTL);
        return link;
    }

    @Override
    public void recordClick(String code, Instant at) {
        store.recordClick(code, at);
    }

    @Override
    public Optional<LinkStats> stats(String code) {
        return store.stats(code);
    }

    private void put(ShortLink link) {
        Duration ttl = MAX_TTL;
        if (link.expiresAt() != null) {
            Duration remaining = Duration.between(clock.instant(), link.expiresAt());
            if (remaining.compareTo(ttl) < 0) ttl = remaining;
        }
        if (ttl.isNegative() || ttl.isZero()) return; // already expired: nothing worth caching
        cacheSet(key(link.code()), encode(link), ttl);
    }

    /** createdAt-millis TAB expiresAt-millis-or-empty TAB url (a validated URL cannot contain a raw tab). */
    static String encode(ShortLink l) {
        return l.createdAt().toEpochMilli() + "\t" + (l.expiresAt() == null ? "" : l.expiresAt().toEpochMilli()) + "\t" + l.originalUrl();
    }

    static ShortLink decode(String code, String value) {
        String[] f = value.split("\t", 3);
        return new ShortLink(code, f[2], Instant.ofEpochMilli(Long.parseLong(f[0])),
                f[1].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(f[1])));
    }

    private Optional<String> cacheGet(String key) {
        try {
            Optional<String> v = cache.get(key);
            healthy();
            return v;
        } catch (RuntimeException e) {
            degrade(e);
            return Optional.empty();
        }
    }

    private void cacheSet(String key, String value, Duration ttl) {
        try {
            cache.set(key, value, ttl);
            healthy();
        } catch (RuntimeException e) {
            degrade(e);
        }
    }

    /** Logged once per outage, not once per request. */
    private void degrade(RuntimeException e) {
        if (!degraded) LOG.log(Level.WARNING, "link cache unavailable, reading through to the store: {0}", e.toString());
        degraded = true;
    }

    private void healthy() {
        if (degraded) LOG.log(Level.INFO, "link cache available again");
        degraded = false;
    }
}
