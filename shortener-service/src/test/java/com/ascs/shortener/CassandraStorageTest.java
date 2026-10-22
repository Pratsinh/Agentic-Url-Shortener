package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.Base62CodeGenerator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.cassandra.CassandraLinkRepository;
import com.ascs.shortener.infra.cassandra.CassandraSchema;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** The Cassandra adapter against a real Cassandra (running server or Docker, see {@link TestInfra}). */
@EnabledIf("com.ascs.shortener.TestInfra#cassandraAvailable")
class CassandraStorageTest {

    private static final String KEYSPACE = "shortener_it";
    private static CqlSession session;

    static CqlSession connect() {
        return CqlSession.builder().addContactPoint(TestInfra.cassandraContactPoint())
                .withLocalDatacenter(TestInfra.cassandraDatacenter()).build();
    }

    @BeforeAll
    static void setUp() {
        session = connect();
        // repeatable on a long-lived local server; DDL needs the schema timeout, not the 2 s request default
        session.execute(SimpleStatement.newInstance("DROP KEYSPACE IF EXISTS " + KEYSPACE).setTimeout(CassandraSchema.DDL_TIMEOUT));
        CassandraSchema.create(session, KEYSPACE, 1);
    }

    @AfterAll
    static void tearDown() {
        if (session != null) session.close();
    }

    @Test
    @Verifies({"AC-9.1"})
    void linksAreDurableAcrossSessions() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        CassandraLinkRepository repo = new CassandraLinkRepository(session, KEYSPACE);
        assertTrue(repo.saveIfAbsent(new ShortLink("durable1", "https://example.com/x", now, now.plusSeconds(3600))));
        assertTrue(repo.saveIfAbsent(new ShortLink("noexpiry", "https://example.com/y", now, null)));

        try (CqlSession restarted = connect()) { // a fresh session: nothing can come from client-side state
            CassandraLinkRepository again = new CassandraLinkRepository(restarted, KEYSPACE);
            assertEquals(new ShortLink("durable1", "https://example.com/x", now, now.plusSeconds(3600)), again.find("durable1").orElseThrow());
            assertNull(again.find("noexpiry").orElseThrow().expiresAt());
            assertTrue(again.find("missing1").isEmpty());
            assertTrue(again.stats("missing1").isEmpty());
        }
    }

    @Test
    @Verifies({"AC-9.2"})
    void concurrentCreatesOfTheSameCodeStoreExactlyOne() throws Exception {
        CassandraLinkRepository repo = new CassandraLinkRepository(session, KEYSPACE);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String url = "https://example.com/" + i;
                Callable<Boolean> create = () -> repo.saveIfAbsent(new ShortLink("contest1", url, Instant.now(), null));
                results.add(pool.submit(create));
            }
            int applied = 0;
            for (Future<Boolean> r : results) if (r.get()) applied++;
            assertEquals(1, applied);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clicksAreCountedAndLastAccessNeverMovesBackwards() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        CassandraLinkRepository repo = new CassandraLinkRepository(session, KEYSPACE);
        repo.saveIfAbsent(new ShortLink("clicks01", "https://example.com", now, null));
        repo.recordClick("clicks01", now.plusSeconds(10));
        repo.recordClick("clicks01", now.plusSeconds(5)); // older click finishing late
        LinkStats stats = repo.stats("clicks01").orElseThrow();
        assertEquals(2, stats.totalClicks());
        assertEquals(now.plusSeconds(10), stats.lastAccessedAt());

        repo.saveIfAbsent(new ShortLink("unclick1", "https://example.com", now, null));
        assertEquals(0, repo.stats("unclick1").orElseThrow().totalClicks());
        assertNull(repo.stats("unclick1").orElseThrow().lastAccessedAt());
    }

    @Test
    void theServiceWorksEndToEndOnCassandra() {
        ShortenerService svc = new ShortenerService(new CassandraLinkRepository(session, KEYSPACE),
                new Base62CodeGenerator(new SecureRandom(), 7), new UrlValidator(), Clock.systemUTC(), new InMemoryAuditTrail(10));
        ShortLink created = svc.create("https://example.com/service", Duration.ofMinutes(5));
        assertEquals(created, svc.resolve(created.code()), "what was created is exactly what is read back");
        assertEquals(1, svc.stats(created.code()).totalClicks());
    }

    @Test
    void schemaRejectsUnsafeKeyspaceNamesAndReplication() {
        assertThrows(IllegalArgumentException.class, () -> CassandraSchema.create(session, "bad; DROP", 1));
        assertThrows(IllegalArgumentException.class, () -> CassandraSchema.create(session, "ok_name", 0));
    }
}
