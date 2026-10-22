package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.InMemoryLinkRepository;
import com.ascs.shortener.infra.cache.CachingLinkRepository;
import com.ascs.shortener.infra.cache.KeyValueCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cache-aside behaviour of the Redis decorator, with an in-memory cache and a store that counts its reads. */
class CachingLinkRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Records values and TTLs; can be switched off to simulate a Redis outage. */
    static final class FakeCache implements KeyValueCache {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Duration> ttls = new HashMap<>();
        boolean down;

        @Override public Optional<String> get(String key) {
            if (down) throw new IllegalStateException("connection refused");
            return Optional.ofNullable(values.get(key));
        }

        @Override public void set(String key, String value, Duration ttl) {
            if (down) throw new IllegalStateException("connection refused");
            values.put(key, value);
            ttls.put(key, ttl);
        }
    }

    /** The durable store (Cassandra in production), counting how often it is read. */
    static final class CountingStore extends InMemoryLinkRepository {
        final AtomicInteger finds = new AtomicInteger();

        @Override public Optional<ShortLink> find(String code) {
            finds.incrementAndGet();
            return super.find(code);
        }
    }

    private MutableClock clock;
    private FakeCache cache;
    private CountingStore store;
    private CachingLinkRepository repo;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        cache = new FakeCache();
        store = new CountingStore();
        repo = new CachingLinkRepository(store, cache, clock);
    }

    private static ShortLink link(String code, Instant expiresAt) {
        return new ShortLink(code, "https://example.com/a|b?q=1", T0, expiresAt);
    }

    @Test
    @Verifies({"AC-9.3"})
    void cachedLinksAreServedWithoutReadingTheStore() {
        assertTrue(repo.saveIfAbsent(link("primed01", null)));
        assertEquals(Optional.of(link("primed01", null)), repo.find("primed01"));
        assertEquals(0, store.finds.get(), "a create primes the cache");

        store.saveIfAbsent(link("written2", T0.plusSeconds(600))); // e.g. created by another instance
        assertEquals(Optional.of(link("written2", T0.plusSeconds(600))), repo.find("written2"));
        assertEquals(Optional.of(link("written2", T0.plusSeconds(600))), repo.find("written2"));
        assertEquals(1, store.finds.get(), "only the first read misses");
    }

    @Test
    @Verifies({"AC-9.4"})
    void aCacheEntryNeverOutlivesItsLinkAndIsCappedAtADay() {
        repo.saveIfAbsent(link("forever1", null));
        repo.saveIfAbsent(link("tenmin01", T0.plus(Duration.ofMinutes(10))));
        repo.saveIfAbsent(link("twodays1", T0.plus(Duration.ofDays(2))));
        assertEquals(Duration.ofHours(24), cache.ttls.get("link:forever1"));
        assertEquals(Duration.ofMinutes(10), cache.ttls.get("link:tenmin01"));
        assertEquals(Duration.ofHours(24), cache.ttls.get("link:twodays1"));

        store.saveIfAbsent(link("expired1", T0.minusSeconds(1)));
        assertTrue(repo.find("expired1").isPresent(), "expiry is the service's decision (410), the store still knows it");
        assertFalse(cache.values.containsKey("link:expired1"), "an expired link is not worth caching");
        store.saveIfAbsent(link("expiring", T0));
        repo.find("expiring");
        assertFalse(cache.values.containsKey("link:expiring"), "zero remaining life is not cached either");
    }

    @Test
    @Verifies({"AC-9.5"})
    void unknownCodesAreNegativelyCachedUntilCreated() {
        assertTrue(repo.find("guess001").isEmpty());
        assertTrue(repo.find("guess001").isEmpty());
        assertEquals(1, store.finds.get());
        assertEquals(Duration.ofSeconds(60), cache.ttls.get("link:guess001"));

        repo.saveIfAbsent(link("guess001", null));
        assertTrue(repo.find("guess001").isPresent(), "creating the code replaces its negative entry");
    }

    @Test
    @Verifies({"AC-9.6"})
    void aRedisOutageFallsBackToTheStoreAndRecovers() {
        ShortenerService svc = new ShortenerService(repo, () -> "outage01", new UrlValidator(), clock, new InMemoryAuditTrail(10));
        cache.down = true;
        assertEquals("outage01", svc.create("https://example.com", null).code());
        assertEquals("https://example.com", svc.resolve("outage01").originalUrl());
        assertEquals(1, svc.stats("outage01").totalClicks());
        assertTrue(cache.values.isEmpty());

        cache.down = false;
        svc.resolve("outage01");
        assertTrue(cache.values.containsKey("link:outage01"), "caching resumes once Redis is back");
        svc.resolve("outage01");
        assertEquals(2, store.finds.get(), "one read while down, one miss after recovery, then cache hits");
    }

    @Test
    void aLostCreateRaceDoesNotTouchTheCache() {
        store.saveIfAbsent(link("taken001", null));
        assertFalse(repo.saveIfAbsent(link("taken001", T0.plusSeconds(5))));
        assertTrue(cache.values.isEmpty());
        repo.recordClick("taken001", T0);
        assertEquals(1, repo.stats("taken001").orElseThrow().totalClicks());
    }
}
