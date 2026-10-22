package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.AuditEvent;
import com.ascs.shortener.domain.AuditEvent.Action;
import com.ascs.shortener.domain.ShortenerException;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.InMemoryLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Business audit trail of the shortener, and the log/audit hygiene around it. */
class ShortenerAuditTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock;
    private InMemoryAuditTrail audit;
    private InMemoryLinkRepository repo;
    private ShortenerService svc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        audit = new InMemoryAuditTrail(100);
        repo = new InMemoryLinkRepository();
        svc = new ShortenerService(repo, () -> "abcd1234", new UrlValidator(), clock, audit);
    }

    private List<Action> actions() {
        return audit.recent(100).stream().map(AuditEvent::action).toList();
    }

    @Test
    @Verifies({"AC-5.1"})
    void everyCreateAndRedirectIsAuditedWhetherItSucceedsOrNot() {
        svc.create("https://example.com/a", Duration.ofMinutes(5));
        assertThrows(ShortenerException.class, () -> svc.create("ftp://example.com", null));
        svc.resolve("abcd1234");
        assertThrows(ShortenerException.NotFound.class, () -> svc.resolve("zzzz9999"));
        clock.advance(Duration.ofMinutes(5));
        assertThrows(ShortenerException.Expired.class, () -> svc.resolve("abcd1234"));

        assertEquals(List.of(Action.LINK_CREATED, Action.LINK_CREATE_REJECTED, Action.LINK_REDIRECTED,
                Action.LINK_REDIRECT_REJECTED, Action.LINK_REDIRECT_REJECTED), actions());
        AuditEvent created = audit.recent(100).get(0);
        assertEquals(0, created.seq());
        assertEquals(T0, created.at());
        assertEquals("abcd1234", created.code());
        assertTrue(created.detail().contains("expiresAt=" + T0.plus(Duration.ofMinutes(5))));
        assertNull(audit.recent(100).get(1).code(), "a rejected create has no link");
    }

    @Test
    @Verifies({"AC-5.2"})
    void auditRecordsTheHostButNeverTheQueryString() {
        svc.create("https://example.com/reset?token=s3cr3t-value", null);
        svc.resolve("abcd1234");

        for (AuditEvent e : audit.recent(100)) {
            assertFalse(e.detail().contains("s3cr3t"), e.toString());
            assertTrue(e.detail().contains("host=example.com"), e.toString());
        }
    }

    @Test
    @Verifies({"AC-5.3"})
    void perLinkHistoryIsAvailableAndUnknownCodesAreNotFound() {
        svc.create("https://example.com", null);
        svc.resolve("abcd1234");
        assertEquals(List.of(Action.LINK_CREATED, Action.LINK_REDIRECTED),
                svc.auditOf("abcd1234").stream().map(AuditEvent::action).toList());
        assertThrows(ShortenerException.NotFound.class, () -> svc.auditOf("nope0000"));
        assertThrows(ShortenerException.NotFound.class, () -> svc.auditOf("bad code"));
        assertThrows(ShortenerException.NotFound.class, () -> svc.stats("bad code"));
    }

    @Test
    void recentAuditLimitIsValidated() {
        svc.create("https://example.com", null);
        assertEquals(1, svc.recentAudit(1).size());
        assertEquals(1, svc.recentAudit(1000).size());
        assertThrows(ShortenerException.InvalidRequest.class, () -> svc.recentAudit(0));
        assertThrows(ShortenerException.InvalidRequest.class, () -> svc.recentAudit(1001));
    }

    @Test
    @Verifies({"AC-6.5"})
    void callerSuppliedCodesAreNeutralisedBeforeBeingLoggedOrAudited() {
        String forged = "x\n2026-01-01 INFO admin logged in";
        ShortenerException e = assertThrows(ShortenerException.NotFound.class, () -> svc.resolve(forged));
        assertFalse(e.getMessage().contains("\n"));
        assertTrue(e.getMessage().contains("x?2026-01-01?INFO?admin?logged?i..."), e.getMessage());

        AuditEvent rejected = audit.recent(1).get(0);
        assertNull(rejected.code(), "a malformed code is never stored as if it were a link code");
        assertFalse(rejected.detail().contains("\n"));
        assertTrue(new ShortenerException.NotFound(null).getMessage().contains("'null'"));
        assertTrue(new ShortenerException.Expired("ab\rcd").getMessage().contains("'ab?cd'"));
    }

    @Test
    void lastAccessIsMonotonicAndClicksOnUnknownCodesAreIgnored() {
        svc.create("https://example.com", null);
        repo.recordClick("abcd1234", T0.plusSeconds(10));
        repo.recordClick("abcd1234", T0.plusSeconds(5)); // a slower, older click finishing late
        assertEquals(T0.plusSeconds(10), repo.stats("abcd1234").orElseThrow().lastAccessedAt());

        repo.recordClick("missing1", T0);
        assertTrue(repo.stats("missing1").isEmpty());
    }

    @Test
    void auditEventRequiresItsMandatoryFields() {
        assertThrows(NullPointerException.class, () -> new AuditEvent(0, null, Action.LINK_CREATED, null, ""));
        assertThrows(NullPointerException.class, () -> new AuditEvent(0, T0, null, null, ""));
        assertThrows(NullPointerException.class, () -> new AuditEvent(0, T0, Action.LINK_CREATED, null, null));
    }
}
