package com.ascs.agentic.vcs;

import com.ascs.agentic.json.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens the pull request through which a human reviews and merges an approved run (GitHub REST API, plain
 * {@code java.net.http}). Opt-in: disabled unless {@code AGENTIC_PR=on}, and then a {@code GITHUB_TOKEN} is required
 * (fine-grained: Contents and Pull requests write on the one repository). The token lives only in this object: it is
 * not part of the configuration, never logged, and {@link #redact} removes it from anything this class reports.
 * This class can open a pull request but has no operation to merge one; merging stays with a human on GitHub.
 *
 * @param remote git remote the run branch is pushed to
 * @param base   branch the pull request targets
 * @param repo   {@code owner/name}; empty means derived from the remote's URL
 * @param api    GitHub API base URL (GitHub Enterprise or a test server)
 */
public record PullRequests(boolean enabled, String remote, String base, String repo, URI api, Secret token) {

    /** A credential whose {@code toString} never reveals it, so a record or log line cannot leak it by accident. */
    public record Secret(String value) {
        @Override
        public String toString() {
            return value == null || value.isBlank() ? "(none)" : "(set)";
        }
    }

    /** What was opened (or found already open for the branch). */
    public record Opened(int number, String url, boolean existed) {}

    static final Pattern GITHUB_URL = Pattern.compile("github\\.com[:/]([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public PullRequests {
        if (enabled && (token == null || token.value() == null || token.value().isBlank())) {
            throw new IllegalArgumentException("AGENTIC_PR=on needs GITHUB_TOKEN (Contents and Pull requests write access)");
        }
        if (!repo.isEmpty() && !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("AGENTIC_PR_REPO must be owner/name");
        if (!base.matches("[A-Za-z0-9._/-]{1,100}")) throw new IllegalArgumentException("invalid AGENTIC_PR_BASE");
    }

    public static PullRequests disabled() {
        return new PullRequests(false, "origin", "main", "", URI.create("https://api.github.com"), new Secret(null));
    }

    public static PullRequests fromEnv(Map<String, String> env) {
        boolean on = "on".equalsIgnoreCase(env.getOrDefault("AGENTIC_PR", "off").strip());
        return new PullRequests(on, env.getOrDefault("AGENTIC_PR_REMOTE", "origin"), env.getOrDefault("AGENTIC_PR_BASE", "main"),
                env.getOrDefault("AGENTIC_PR_REPO", ""), URI.create(env.getOrDefault("AGENTIC_GITHUB_API", "https://api.github.com")),
                new Secret(env.get("GITHUB_TOKEN")));
    }

    /** {@code owner/name} of the target repository: configured, or parsed from the remote's GitHub URL. */
    public String repoFor(String remoteUrl) {
        if (!repo.isEmpty()) return repo;
        Matcher m = GITHUB_URL.matcher(remoteUrl.strip());
        if (!m.find()) throw new IllegalArgumentException("remote '" + remote + "' is not a GitHub repository; set AGENTIC_PR_REPO=owner/name");
        return m.group(1) + "/" + m.group(2);
    }

    /**
     * Opens a pull request from {@code head} into {@link #base}. Idempotent: if GitHub reports that one already exists
     * for the branch (e.g. a retry after a lost response), the open one is returned instead.
     */
    public Opened open(String ownerRepo, String head, String title, String body) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        String payload = Json.compact(Map.of("title", title, "head", head, "base", base, "body", body,
                "maintainer_can_modify", true));
        HttpResponse<String> r = http.send(request("/repos/" + ownerRepo + "/pulls")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 201) {
            JsonNode pr = Json.parse(r.body());
            return new Opened(pr.path("number").asInt(), pr.path("html_url").asText(), false);
        }
        if (r.statusCode() == 422 && r.body().toLowerCase(Locale.ROOT).contains("already exists")) {
            String owner = ownerRepo.substring(0, ownerRepo.indexOf('/'));
            String query = "?state=open&head=" + URLEncoder.encode(owner + ":" + head, StandardCharsets.UTF_8);
            HttpResponse<String> list = http.send(request("/repos/" + ownerRepo + "/pulls" + query).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode prs = list.statusCode() == 200 ? Json.parse(list.body()) : null;
            if (prs != null && prs.isArray() && !prs.isEmpty()) {
                return new Opened(prs.get(0).path("number").asInt(), prs.get(0).path("html_url").asText(), true);
            }
        }
        throw new IOException("GitHub API " + r.statusCode() + " opening the pull request: " + redact(abbreviate(r.body())));
    }

    /** Removes the token from text this class or its callers report. */
    public String redact(String text) {
        String t = token == null ? null : token.value();
        return text == null || t == null || t.isBlank() ? text : text.replace(t, "[REDACTED:token]");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(api.toString().replaceAll("/+$", "") + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token.value())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json");
    }

    private static String abbreviate(String s) {
        String one = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }
}
