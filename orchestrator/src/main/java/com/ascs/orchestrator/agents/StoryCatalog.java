package com.ascs.orchestrator.agents;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Product knowledge the requirements agent draws on: capabilities of the URL shortener and of the governed
 * delivery pipeline, each as a user story with testable acceptance criteria. Acceptance-criterion ids
 * ({@code AC-n.m}) are the contract with the QA agent: tests declare which criteria they verify.
 */
public final class StoryCatalog {
    private StoryCatalog() {}

    public record Criterion(String id, String given, String when, String then) {}

    public record Story(String id, String title, String role, String goal, String benefit,
                        Pattern trigger, List<String> components, List<Criterion> criteria) {}

    private static Pattern kw(String regex) {
        return Pattern.compile(regex);
    }

    private static Criterion ac(String id, String given, String when, String then) {
        return new Criterion(id, given, when, then);
    }

    public static final List<Story> ALL = List.of(
            new Story("US-1", "Shorten a URL", "link creator", "to turn a long URL into a short code",
                    "I can share a compact link", kw("shorten|create|url shortener"),
                    List.of("shortener.web", "shortener.app", "shortener.domain", "shortener.infra"), List.of(
                    ac("AC-1.1", "a valid public http(s) URL", "I POST it to /api/v1/links",
                            "I get 201 with a code, the short URL and a Location header"),
                    ac("AC-1.2", "a missing, malformed, non-http(s) or credential-bearing URL", "I submit it",
                            "I get 400 and nothing is stored"),
                    ac("AC-1.3", "a URL whose host is loopback, private, link-local or an obfuscated IP", "I submit it",
                            "I get 400 (SSRF guard)"),
                    ac("AC-1.4", "a TTL that is zero, negative or longer than 365 days", "I submit it",
                            "I get 400"),
                    ac("AC-1.5", "every generated code collides", "the service has retried 5 times",
                            "I get 503 instead of an overwritten link"))),
            new Story("US-2", "Redirect", "visitor", "a short link to take me to the original URL",
                    "short links work like the long ones", kw("redirect|url shortener"),
                    List.of("shortener.web", "shortener.app"), List.of(
                    ac("AC-2.1", "an existing, unexpired code", "I GET /{code}", "I get 302 to the original URL"),
                    ac("AC-2.2", "an unknown or malformed code", "I GET it", "I get 404"),
                    ac("AC-2.3", "a redirect request", "it succeeds or fails",
                            "only successful redirects increment the click count"))),
            new Story("US-3", "Link expiry", "link creator", "links to stop working after a TTL",
                    "temporary links do not live forever", kw("expir|ttl"),
                    List.of("shortener.domain", "shortener.app"), List.of(
                    ac("AC-3.1", "a link with a TTL", "the TTL has elapsed (boundary inclusive)", "redirect returns 410"),
                    ac("AC-3.2", "a link without a TTL", "any amount of time passes", "it still redirects"))),
            new Story("US-4", "Click analytics", "link owner", "to see how often my link was used",
                    "I can measure reach", kw("analytic|click|stat"),
                    List.of("shortener.web", "shortener.app", "shortener.infra"), List.of(
                    ac("AC-4.1", "a link that was clicked", "I GET /api/v1/links/{code}/stats",
                            "I see the URL, total clicks, created/expiry time and last access"),
                    ac("AC-4.2", "an expired link", "I request its stats", "they are still returned"),
                    ac("AC-4.3", "thousands of concurrent redirects", "they complete", "every click is counted exactly once"))),
            new Story("US-5", "Audit trail", "operator", "a record of every create and redirect",
                    "I can investigate abuse and incidents", kw("audit"),
                    List.of("shortener.app", "shortener.infra", "shortener.web"), List.of(
                    ac("AC-5.1", "any create or redirect", "it succeeds or is rejected", "an audit event records the outcome"),
                    ac("AC-5.2", "a URL with a query string", "it is audited or logged", "only its host is recorded"),
                    ac("AC-5.3", "an existing link", "I GET /api/v1/links/{code}/audit",
                            "I see its history; unknown codes return 404"),
                    ac("AC-5.4", "the audit capacity is reached", "new events arrive", "the oldest events are evicted"))),
            new Story("US-6", "Errors and observability", "API client and operator", "consistent errors and traceable logs",
                    "failures are diagnosable without leaking internals", kw("error|log|observab"),
                    List.of("shortener.web", "shortener.domain"), List.of(
                    ac("AC-6.1", "any rejected request", "the API responds", "the body is an RFC 7807 problem"),
                    ac("AC-6.2", "a malformed or missing JSON body", "I POST it", "I get a 400 problem"),
                    ac("AC-6.3", "an unexpected server failure", "the API responds",
                            "I get a 500 problem with a reference id and no stack trace"),
                    ac("AC-6.4", "any request", "it is processed",
                            "the response carries X-Request-Id (a safe incoming id is reused, others replaced)"),
                    ac("AC-6.5", "a code containing newlines or control characters", "it is logged or audited",
                            "it is neutralised so log lines cannot be forged"))),
            new Story("US-7", "Governed agent workflow", "engineering lead", "agents to work inside explicit guardrails",
                    "autonomy never bypasses review", kw("govern|orchestrat|agent"),
                    List.of("orchestrator.core", "orchestrator.policy", "orchestrator.approval", "orchestrator.audit"), List.of(
                    ac("AC-7.1", "a stage graph", "the run executes", "stages respect dependencies and independent stages overlap"),
                    ac("AC-7.2", "an agent that fails", "attempts remain", "it is retried within the bound, then its fallback runs"),
                    ac("AC-7.3", "a HIGH-impact stage", "its agent finishes",
                            "outputs commit only after human approval; rejection or timeout blocks them"),
                    ac("AC-7.4", "a CRITICAL policy violation", "it is detected", "the run safe-stops without retrying"),
                    ac("AC-7.5", "a stage failing with ROLLBACK_RUN", "earlier stages succeeded",
                            "they are compensated in reverse order"),
                    ac("AC-7.6", "the audit log", "any event is altered", "verification detects the tampering"))),
            new Story("US-8", "Agentic delivery roles", "engineering lead", "role agents that produce SDLC evidence",
                    "every release ships with requirements, design, review and QA evidence", kw("agent|deliver|sdlc"),
                    List.of("orchestrator.agents"), List.of(
                    ac("AC-8.1", "a requirement", "the requirements agent runs", "it emits user stories with acceptance criteria"),
                    ac("AC-8.2", "the stories and the source tree", "the design agent runs",
                            "it emits a design with diagrams and an architecture-conformance check"),
                    ac("AC-8.3", "the git repository", "the development agent runs",
                            "it checks commit-message quality and proposes commits for pending changes"),
                    ac("AC-8.4", "all source files", "the code-review agent runs",
                            "every file is listed as reviewed with findings and their resolution"),
                    ac("AC-8.5", "coverage data and tests", "the QA agent runs",
                            "it reports unit and functional coverage against the target and lists every gap"))),
            new Story("US-9", "Durable storage and caching", "operator", "links stored in Cassandra and redirects served from a Redis cache",
                    "links survive restarts and hot links stay fast", kw("cassandra|redis|cach|persist|durab"),
                    List.of("shortener.infra.cassandra", "shortener.infra.redis", "shortener.infra.cache"), List.of(
                    ac("AC-9.1", "a link created with Cassandra storage", "the service restarts", "the link still resolves"),
                    ac("AC-9.2", "two concurrent creates with the same code", "both reach Cassandra",
                            "exactly one is stored (lightweight transaction) and the other is retried"),
                    ac("AC-9.3", "a cached link", "it is resolved again", "it is served from Redis without reading Cassandra"),
                    ac("AC-9.4", "a link with a TTL", "it is cached", "the cache entry expires no later than the link (max 24 h)"),
                    ac("AC-9.5", "an unknown code", "it is requested repeatedly", "only the first request reaches Cassandra for 60 s"),
                    ac("AC-9.6", "Redis is unavailable", "a link is created or resolved", "requests read through to Cassandra and still succeed"))),
            new Story("US-10", "Published API contract", "API client (including the orchestrator)",
                    "the shortener's API published as an OpenAPI contract that the service provably implements",
                    "clients are generated from the contract and never depend on the service's code", kw("openapi|api contract"),
                    List.of("shortener.web"), List.of(
                    ac("AC-10.1", "the running service", "I GET /api/v1/openapi.yaml", "I get the OpenAPI contract it implements"),
                    ac("AC-10.2", "the contract and the controllers", "the build runs",
                            "every documented operation is implemented and every endpoint is documented"),
                    ac("AC-10.3", "a create, redirect, stats and audit flow", "the responses are checked against the contract",
                            "every field is documented and every required field is present"))),
            new Story("US-11", "Governed tool access", "engineering lead",
                    "agents to reach external systems only through a governed tool gateway",
                    "an LLM can use real APIs without ever bypassing permissions, policies or human approval",
                    kw("tool|gateway|openapi|api contract"),
                    List.of("orchestrator.tools"), List.of(
                    ac("AC-11.1", "a proposed tool call", "the tool is unknown, not granted to the stage, or its arguments do not fit its spec",
                            "it is denied without being executed and the denial is audited"),
                    ac("AC-11.2", "a MUTATING tool call", "it is proposed",
                            "it runs only after human approval; rejection, timeout or a failing approval channel denies it"),
                    ac("AC-11.3", "tool arguments or a tool output containing a credential", "the call is processed",
                            "the call is blocked or the output withheld, and the credential reaches neither the audit log nor the planner"),
                    ac("AC-11.4", "a transient failure of the target system", "a tool call fails",
                            "a read-only call is retried within its bound and a mutating call is never retried"),
                    ac("AC-11.5", "a CRITICAL tool-policy violation or an exhausted call budget", "further calls are proposed in the run",
                            "they are all denied"),
                    ac("AC-11.6", "a tool-using agent in a workflow stage", "it runs",
                            "its planner sees only the granted tools, results are framed as untrusted data, and a planner that never finishes fails the attempt"),
                    ac("AC-11.7", "the shortener's OpenAPI contract", "the shortener tools are built and called",
                            "names, parameters and risk come from the contract and every call carries its correlation id as X-Request-Id"),
                    ac("AC-11.8", "a WORKSPACE_WRITE tool call", "it is proposed",
                            "it needs no per-call approval, is never retried, and policies see the calling run and stage (least privilege)"))));

    /** Stories whose trigger matches the requirement text, in catalog order. */
    public static List<Story> select(String requirement) {
        String text = requirement.toLowerCase(Locale.ROOT);
        return ALL.stream().filter(s -> s.trigger().matcher(text).find()).toList();
    }
}
