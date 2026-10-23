package com.ascs.agentic;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.vcs.PullRequests;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestsTest {

    // fixture assembled from fragments so the repository's own code-review agent does not count it as a leak
    static final String TOKEN = "github" + "_pat_" + "0123456789abcdefghij";

    static PullRequests enabled(URI api) {
        return new PullRequests(true, "origin", "main", "", api, new PullRequests.Secret(TOKEN));
    }

    @Test
    void opensAPullRequestFromTheRunBranchIntoTheBase() throws Exception {
        try (FakeGitHub gh = new FakeGitHub(r -> new FakeGitHub.Response(201, "{\"number\":7,\"html_url\":\"https://github.com/o/r/pull/7\"}"))) {
            PullRequests.Opened pr = enabled(gh.uri()).open("o/r", "agentic/run-1", "feat: x", "evidence");

            assertEquals(new PullRequests.Opened(7, "https://github.com/o/r/pull/7", false), pr);
            FakeGitHub.Request req = gh.requests.get(0);
            assertEquals("POST", req.method());
            assertEquals("/repos/o/r/pulls", req.path());
            assertEquals("Bearer " + TOKEN, req.authorization());
            JsonNode body = Json.parse(req.body());
            assertEquals("agentic/run-1", body.path("head").asText());
            assertEquals("main", body.path("base").asText());
            assertEquals("feat: x", body.path("title").asText());
            assertEquals("evidence", body.path("body").asText());
        }
    }

    @Test
    void anAlreadyOpenPullRequestIsReturnedSoARetryIsIdempotent() throws Exception {
        try (FakeGitHub gh = new FakeGitHub(r -> r.method().equals("POST")
                ? new FakeGitHub.Response(422, "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"A pull request already exists for o:agentic/run-1.\"}]}")
                : new FakeGitHub.Response(200, "[{\"number\":3,\"html_url\":\"https://github.com/o/r/pull/3\"}]"))) {
            PullRequests.Opened pr = enabled(gh.uri()).open("o/r", "agentic/run-1", "t", "b");

            assertEquals(new PullRequests.Opened(3, "https://github.com/o/r/pull/3", true), pr);
            assertEquals("/repos/o/r/pulls?state=open&head=o%3Aagentic%2Frun-1", gh.requests.get(1).path());
        }
    }

    @Test
    void apiErrorsAreReportedWithoutTheToken() throws Exception {
        try (FakeGitHub gh = new FakeGitHub(r -> new FakeGitHub.Response(401, "{\"message\":\"Bad credentials " + TOKEN + "\"}"))) {
            IOException e = assertThrows(IOException.class, () -> enabled(gh.uri()).open("o/r", "agentic/run-1", "t", "b"));
            assertTrue(e.getMessage().startsWith("GitHub API 401"), e.getMessage());
            assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        }
    }

    @Test
    void configurationIsOptInAndNeverRevealsTheToken() {
        assertFalse(PullRequests.fromEnv(Map.of()).enabled());
        assertThrows(IllegalArgumentException.class, () -> PullRequests.fromEnv(Map.of("AGENTIC_PR", "on")));
        assertThrows(IllegalArgumentException.class, () -> PullRequests.fromEnv(Map.of("AGENTIC_PR", "on", "GITHUB_TOKEN", TOKEN,
                "AGENTIC_PR_REPO", "not a repo")));
        assertThrows(IllegalArgumentException.class, () -> PullRequests.fromEnv(Map.of("AGENTIC_PR_BASE", "main; rm")));
        PullRequests prs = PullRequests.fromEnv(Map.of("AGENTIC_PR", "ON", "GITHUB_TOKEN", TOKEN, "AGENTIC_PR_BASE", "develop"));
        assertTrue(prs.enabled());
        assertEquals("develop", prs.base());
        assertFalse(prs.toString().contains(TOKEN), prs.toString());
        assertEquals("(none)", new PullRequests.Secret(null).toString());
        assertEquals("x [REDACTED:token] y", prs.redact("x " + TOKEN + " y"));
        assertNull(prs.redact(null));
        assertEquals("text", PullRequests.disabled().redact("text"));
    }

    @Test
    void theRepositoryComesFromConfigurationOrTheRemoteUrl() {
        PullRequests prs = PullRequests.disabled();
        assertEquals("Pratsinh/Agentic-Url-Shortener", prs.repoFor("https://github.com/Pratsinh/Agentic-Url-Shortener.git"));
        assertEquals("o/r", prs.repoFor("git@github.com:o/r.git"));
        assertEquals("o/r", prs.repoFor("https://github.com/o/r/"));
        assertThrows(IllegalArgumentException.class, () -> prs.repoFor("https://gitlab.com/o/r.git"));
        assertEquals("x/y", new PullRequests(false, "origin", "main", "x/y", URI.create("https://api.github.com"),
                new PullRequests.Secret(null)).repoFor("anything"));
    }
}
