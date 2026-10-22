package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.AuditEvent;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.ShortenerException;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.InMemoryLinkRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** What the service does when the durable store (Cassandra) is down. */
class StorageOutageTest {

    /** A store whose individual operations can be made to fail like an unreachable Cassandra. */
    static final class FlakyStore extends InMemoryLinkRepository {
        boolean savesFail, findsFail, clicksFail;

        private static ShortenerException.StorageUnavailable down() {
            return new ShortenerException.StorageUnavailable(new IllegalStateException("All host(s) tried for query failed"));
        }

        @Override public boolean saveIfAbsent(ShortLink link) { if (savesFail) throw down(); return super.saveIfAbsent(link); }
        @Override public Optional<ShortLink> find(String code) { if (findsFail) throw down(); return super.find(code); }
        @Override public void recordClick(String code, Instant at) { if (clicksFail) throw down(); super.recordClick(code, at); }
    }

    private final FlakyStore store = new FlakyStore();
    private final InMemoryAuditTrail audit = new InMemoryAuditTrail(20);
    private final ShortenerService svc = new ShortenerService(store, () -> "store001", new UrlValidator(),
            new MutableClock(Instant.parse("2026-01-01T00:00:00Z")), audit);

    @Test
    @Verifies({"AC-9.6"})
    void aRedirectSucceedsEvenIfItsClickCannotBeStored() {
        svc.create("https://example.com", null);
        store.clicksFail = true;
        assertEquals("https://example.com", svc.resolve("store001").originalUrl());
        assertEquals(0, svc.stats("store001").totalClicks(), "the lost click is the accepted cost of staying available");
        assertEquals(AuditEvent.Action.LINK_REDIRECTED, audit.recent(1).get(0).action());
    }

    @Test
    void createsAndUncachedReadsFailAsUnavailableAndAreAudited() {
        store.savesFail = true;
        ShortenerException e = assertThrows(ShortenerException.StorageUnavailable.class, () -> svc.create("https://example.com", null));
        assertEquals("link storage is temporarily unavailable", e.getMessage());
        assertNotNull(e.getCause(), "the driver error is kept for the log");
        assertEquals(AuditEvent.Action.LINK_CREATE_REJECTED, audit.recent(1).get(0).action());

        store.savesFail = false;
        svc.create("https://example.com", null);
        store.findsFail = true;
        assertThrows(ShortenerException.StorageUnavailable.class, () -> svc.resolve("store001"));
        assertEquals(AuditEvent.Action.LINK_REDIRECT_REJECTED, audit.recent(1).get(0).action());
    }
}
