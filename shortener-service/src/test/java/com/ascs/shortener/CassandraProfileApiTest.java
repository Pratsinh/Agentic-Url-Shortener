package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.infra.redis.RedisKeyValueCache;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP API wired exactly as the "cassandra" profile wires it, against a real Cassandra, plus the Redis cache when
 * Redis is available too (running servers or Docker, see {@link TestInfra}).
 */
@EnabledIf("com.ascs.shortener.TestInfra#cassandraAvailable")
@SpringBootTest
@AutoConfigureMockMvc
class CassandraProfileApiTest {

    static final boolean WITH_REDIS = TestInfra.redisAvailable();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        InetSocketAddress cassandra = TestInfra.cassandraContactPoint();
        r.add("shortener.storage", () -> "cassandra");
        r.add("spring.cassandra.contact-points", () -> cassandra.getHostString() + ":" + cassandra.getPort());
        r.add("spring.cassandra.local-datacenter", TestInfra::cassandraDatacenter);
        r.add("shortener.cassandra.keyspace", () -> "shortener_api");
        r.add("shortener.cache.redis-url", () -> WITH_REDIS ? TestInfra.redisUrl() : "");
    }

    @Autowired
    MockMvc mvc;

    @Test
    @Verifies({"AC-1.1", "AC-2.1", "AC-4.1", "AC-9.1"})
    void createRedirectAndStatsWorkOnCassandra() throws Exception {
        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/stored\",\"ttlSeconds\":600}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortUrl").value(org.hamcrest.Matchers.matchesPattern("http://localhost:8080/[0-9A-Za-z]{7}")))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");

        mvc.perform(get("/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/stored"));
        mvc.perform(get("/api/v1/links/" + code + "/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1));
        mvc.perform(get("/Unknown9")).andExpect(status().isNotFound());
    }

    @Test
    @Verifies({"AC-9.3"})
    void linksAndUnknownCodesAreCachedInRedis() throws Exception {
        Assumptions.assumeTrue(WITH_REDIS, "Redis not available: the cassandra profile runs without a cache");
        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/cached\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");
        mvc.perform(get("/Unknown8")).andExpect(status().isNotFound());

        try (RedisKeyValueCache redis = new RedisKeyValueCache(TestInfra.redisUrl(), Duration.ofSeconds(2))) {
            assertTrue(redis.get("link:" + code).isPresent(), "the link is cached in Redis");
            assertTrue(redis.get("link:Unknown8").isPresent(), "the unknown code is negatively cached");
        }
    }
}
