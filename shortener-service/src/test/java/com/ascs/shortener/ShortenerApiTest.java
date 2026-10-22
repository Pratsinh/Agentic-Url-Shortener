package com.ascs.shortener;

import com.ascs.Verifies;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level integration test through the full Spring context (runs under Maven only). */
@SpringBootTest
@AutoConfigureMockMvc
class ShortenerApiTest {

    @Autowired
    MockMvc mvc;

    private String createLink(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    @Test
    @Verifies({"AC-1.1", "AC-2.1", "AC-4.1"})
    void createRedirectAndStatsFlow() throws Exception {
        String code = createLink("{\"url\":\"https://example.com/a\"}");

        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/a"));

        mvc.perform(get("/api/v1/links/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1));
    }

    @Test
    @Verifies({"AC-1.2", "AC-1.3"})
    void invalidUrlIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Verifies({"AC-2.2", "AC-6.2"})
    void unknownCodeIsNotFoundAndMissingBodyIsBadRequest() throws Exception {
        mvc.perform(get("/Zzzzzzz")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Verifies({"AC-6.1", "AC-6.2"})
    void malformedJsonAndBadInputAreProblemResponses() throws Exception {
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.com\",\"ttlSeconds\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("ttl must be positive and at most 365 days"));
        mvc.perform(get("/api/v1/audit?limit=0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @Verifies({"AC-5.1", "AC-5.3"})
    void auditEndpointsExposeLinkHistoryAndRecentActivity() throws Exception {
        String code = createLink("{\"url\":\"https://example.com/audited?session=abc123\"}");
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        mvc.perform(get("/api/v1/links/" + code + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("LINK_CREATED"))
                .andExpect(jsonPath("$[0].detail").value("host=example.com"))
                .andExpect(jsonPath("$[1].action").value("LINK_REDIRECTED"));
        mvc.perform(get("/api/v1/audit?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value(code));
        mvc.perform(get("/api/v1/links/Unknown1/audit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Verifies({"AC-6.4"})
    void everyResponseCarriesARequestIdAndForgedIdsAreReplaced() throws Exception {
        mvc.perform(get("/api/v1/audit").header("X-Request-Id", "trace-42"))
                .andExpect(header().string("X-Request-Id", "trace-42"));
        mvc.perform(get("/api/v1/audit").header("X-Request-Id", "bad id\r\nINFO forged"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(get("/Zzzzzzz"))
                .andExpect(header().exists("X-Request-Id"));
    }
}
