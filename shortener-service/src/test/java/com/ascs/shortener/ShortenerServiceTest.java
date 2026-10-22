package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.app.CodeGenerator;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.ShortenerException;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.InMemoryLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ShortenerServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock;
    private InMemoryLinkRepository repo;
    private InMemoryAuditTrail audit;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        repo = new InMemoryLinkRepository();
        audit = new InMemoryAuditTrail(100);
    }

    private ShortenerService serviceWith(CodeGenerator gen) {
        return new ShortenerService(repo, gen, new UrlValidator(), clock, audit);
    }

    private static CodeGenerator sequence(String... codes) {
        Iterator<String> it = List.of(codes).iterator();
        return it::next;
    }

    @Test
    @Verifies({"AC-1.1", "AC-2.1", "AC-3.2"})
    void createsAndResolvesLink() {
        ShortenerService svc = serviceWith(sequence("abcd1234"));
        ShortLink created = svc.create("https://example.com/page", null);
        assertEquals("abcd1234", created.code());
        assertNull(created.expiresAt());

        ShortLink resolved = svc.resolve("abcd1234");
        assertEquals("https://example.com/page", resolved.originalUrl());
    }

    @Test
    @Verifies({"AC-1.2", "AC-1.4"})
    void rejectsInvalidUrlAndInvalidTtl() {
        ShortenerService svc = serviceWith(sequence("aaaa"));
        assertThrows(ShortenerException.InvalidRequest.class, () -> svc.create("http://localhost/x", null));
        assertThrows(ShortenerException.InvalidRequest.class,
                () -> svc.create("https://example.com", Duration.ZERO));
        assertThrows(ShortenerException.InvalidRequest.class,
                () -> svc.create("https://example.com", Duration.ofSeconds(-5)));
        assertThrows(ShortenerException.InvalidRequest.class,
                () -> svc.create("https://example.com", ShortenerService.MAX_TTL.plusSeconds(1)));
    }

    @Test
    @Verifies({"AC-2.2"})
    void unknownOrMalformedCodeIsNotFound() {
        ShortenerService svc = serviceWith(sequence("aaaa"));
        assertThrows(ShortenerException.NotFound.class, () -> svc.resolve("zzzz"));
        assertThrows(ShortenerException.NotFound.class, () -> svc.resolve("../etc/passwd"));
        assertThrows(ShortenerException.NotFound.class, () -> svc.resolve(null));
        assertThrows(ShortenerException.NotFound.class, () -> svc.stats("zzzz"));
    }

    @Test
    @Verifies({"AC-3.1", "AC-2.3", "AC-4.2"})
    void linkExpiresExactlyAtTtlAndExpiredClicksAreNotCounted() {
        ShortenerService svc = serviceWith(sequence("expi"));
        svc.create("https://example.com", Duration.ofMinutes(10));

        clock.advance(Duration.ofMinutes(9));
        svc.resolve("expi"); // still valid

        clock.advance(Duration.ofMinutes(1)); // now == expiresAt -> expired
        assertThrows(ShortenerException.Expired.class, () -> svc.resolve("expi"));

        LinkStats stats = svc.stats("expi"); // stats survive expiry
        assertEquals(1, stats.totalClicks());
    }

    @Test
    @Verifies({"AC-4.1", "AC-2.3"})
    void statsTrackClicksAndLastAccess() {
        ShortenerService svc = serviceWith(sequence("stat"));
        svc.create("https://example.com", null);
        assertEquals(0, svc.stats("stat").totalClicks());
        assertNull(svc.stats("stat").lastAccessedAt());

        svc.resolve("stat");
        clock.advance(Duration.ofSeconds(30));
        svc.resolve("stat");

        LinkStats stats = svc.stats("stat");
        assertEquals(2, stats.totalClicks());
        assertEquals(T0.plusSeconds(30), stats.lastAccessedAt());
        assertEquals(T0, stats.createdAt());
    }

    @Test
    @Verifies({"AC-1.5"})
    void retriesOnCodeCollision() {
        ShortenerService svc = serviceWith(sequence("aaaa", "aaaa", "bbbb"));
        assertEquals("aaaa", svc.create("https://example.com/1", null).code());
        assertEquals("bbbb", svc.create("https://example.com/2", null).code()); // collided once, then succeeded
        assertEquals("https://example.com/1", svc.resolve("aaaa").originalUrl()); // first link not overwritten
    }

    @Test
    @Verifies({"AC-1.5"})
    void failsAfterBoundedCollisionRetries() {
        ShortenerService svc = serviceWith(() -> "same");
        svc.create("https://example.com/1", null);
        assertThrows(ShortenerException.CodeExhausted.class, () -> svc.create("https://example.com/2", null));
    }

    @Test
    @Verifies({"AC-4.3"})
    void concurrentClicksAreCountedExactly() throws Exception {
        ShortenerService svc = serviceWith(sequence("busy"));
        svc.create("https://example.com", null);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 2000; i++) tasks.add(() -> svc.resolve("busy"));
            for (Future<Object> f : pool.invokeAll(tasks)) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2000, svc.stats("busy").totalClicks());
    }
}
