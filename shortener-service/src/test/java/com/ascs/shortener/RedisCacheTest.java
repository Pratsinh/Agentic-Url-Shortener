package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.Base62CodeGenerator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.cache.CachingLinkRepository;
import com.ascs.shortener.infra.cassandra.CassandraLinkRepository;
import com.ascs.shortener.infra.cassandra.CassandraSchema;
import com.ascs.shortener.infra.redis.RedisKeyValueCache;
import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redis in front of Cassandra, both real (running servers or Docker, see {@link TestInfra}). */
@EnabledIf("com.ascs.shortener.TestInfra#cassandraAndRedisAvailable")
class RedisCacheTest {

    private static CqlSession session;
    private static RedisKeyValueCache redis;

    @BeforeAll
    static void setUp() {
        session = CassandraStorageTest.connect();
        CassandraSchema.create(session, "shortener_cache_it", 1);
        redis = new RedisKeyValueCache(TestInfra.redisUrl(), Duration.ofSeconds(2));
    }

    @AfterAll
    static void tearDown() {
        if (redis != null) redis.close();
        if (session != null) session.close();
    }

    @Test
    @Verifies({"AC-9.3", "AC-9.4"})
    void theFullStackCachesInRedisWithABoundedTtl() {
        CachingLinkRepository repo = new CachingLinkRepository(new CassandraLinkRepository(session, "shortener_cache_it"), redis, Clock.systemUTC());
        ShortenerService svc = new ShortenerService(repo, new Base62CodeGenerator(new SecureRandom(), 7),
                new UrlValidator(), Clock.systemUTC(), new InMemoryAuditTrail(100));

        ShortLink created = svc.create("https://example.com/full-stack", Duration.ofMinutes(5));
        assertEquals("https://example.com/full-stack", svc.resolve(created.code()).originalUrl());
        assertEquals(1, svc.stats(created.code()).totalClicks());
        assertTrue(redis.get("link:" + created.code()).isPresent());
        Duration ttl = redis.ttl("link:" + created.code()).orElseThrow();
        assertTrue(ttl.compareTo(Duration.ofMinutes(5)) <= 0 && ttl.compareTo(Duration.ofMinutes(4)) > 0, ttl.toString());
        assertTrue(redis.ttl("link:never-set").isEmpty());
    }
}
