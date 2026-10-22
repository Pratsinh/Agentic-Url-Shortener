package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.app.ShortenerService;
import com.ascs.shortener.domain.LinkStats;
import com.ascs.shortener.domain.ShortLink;
import com.ascs.shortener.domain.UrlValidator;
import com.ascs.shortener.infra.InMemoryAuditTrail;
import com.ascs.shortener.infra.InMemoryLinkRepository;
import com.ascs.shortener.domain.ShortenerException;
import com.ascs.shortener.web.ShortenerController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer error contract with the use cases mocked, so every failure mode can be forced. */
@WebMvcTest(ShortenerController.class)
@TestPropertySource(properties = "shortener.base-url=https://sho.rt/")
@Import(ApiErrorHandlingTest.Stubs.class)
class ApiErrorHandlingTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Hand-written stub (no mocking library): each input forces one failure mode. */
    @TestConfiguration
    static class Stubs {
        @Bean
        ShortenerService service() {
            return new ShortenerService(new InMemoryLinkRepository(), () -> "unused0", new UrlValidator(), Clock.systemUTC(),
                    new InMemoryAuditTrail(10)) {
                @Override public ShortLink create(String url, Duration ttl) {
                    if (ttl == null) throw new ShortenerException.CodeExhausted(5);
                    return new ShortLink("abcd1234", url, NOW, NOW.plus(ttl));
                }
                @Override public ShortLink resolve(String code) {
                    if (code.equals("down0000")) throw new ShortenerException.StorageUnavailable(new IllegalStateException("no nodes at 10.0.0.7"));
                    throw new ShortenerException.Expired(code);
                }
                @Override public LinkStats stats(String code) {
                    throw new IllegalStateException("db password=hunter2 at Foo.java:42");
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    @Verifies({"AC-6.1", "AC-3.1"})
    void domainFailuresMapToProblemResponses() throws Exception {
        mvc.perform(get("/gone1234"))
                .andExpect(status().isGone())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("link 'gone1234' has expired"));
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/down0000"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("link storage is temporarily unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("10.0.0.7"))));
    }

    @Test
    @Verifies({"AC-6.3"})
    void unexpectedFailureIsA500WithAReferenceAndNoInternals() throws Exception {
        mvc.perform(get("/api/v1/links/abcd1234/stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorRef").isString())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("unexpected error; quote reference ")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
    }

    @Test
    @Verifies({"AC-1.1", "AC-6.4"})
    void createUsesTheConfiguredBaseUrlAndPassesTheTtl() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "client-trace-1")
                        .content("{\"url\":\"https://example.com\",\"ttlSeconds\":60}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "https://sho.rt/abcd1234"))
                .andExpect(header().string("X-Request-Id", "client-trace-1"))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/abcd1234"))
                .andExpect(jsonPath("$.expiresAt").value("2026-01-01T00:01:00Z"));
    }
}
