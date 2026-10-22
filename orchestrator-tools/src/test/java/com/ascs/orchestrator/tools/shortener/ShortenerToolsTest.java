package com.ascs.orchestrator.tools.shortener;

import com.ascs.Verifies;
import com.ascs.contract.shortener.ApiException;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.model.RunOutcome;
import com.ascs.orchestrator.tools.Risk;
import com.ascs.orchestrator.tools.Tool;
import com.ascs.orchestrator.tools.ToolCall;
import com.ascs.orchestrator.tools.ToolGateway;
import com.ascs.orchestrator.tools.ToolOutcome;
import com.ascs.orchestrator.tools.ToolParam;
import com.ascs.orchestrator.tools.ToolResult;
import com.ascs.orchestrator.tools.ToolSpec;
import com.ascs.orchestrator.tools.ToolUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The shortener tools: specs derived from the contract, and real HTTP calls through the generated client. */
class ShortenerToolsTest {

    /** One request the stub received. */
    record Seen(String method, String path, String requestId, String body) {}

    private static final String CREATED = "{\"code\":\"abc1234\",\"shortUrl\":\"http://s/abc1234\",\"originalUrl\":\"https://example.com/x\","
            + "\"createdAt\":\"2026-10-22T00:00:00Z\",\"expiresAt\":\"2026-10-22T01:00:00Z\"}";
    private static final String STATS = "{\"code\":\"abc1234\",\"originalUrl\":\"https://example.com/x\",\"totalClicks\":2,"
            + "\"createdAt\":\"2026-10-22T00:00:00Z\",\"expiresAt\":null,\"lastAccessedAt\":null}";
    private static final String AUDIT = "[{\"seq\":0,\"at\":\"2026-10-22T00:00:00Z\",\"action\":\"LINK_CREATED\",\"code\":\"abc1234\",\"detail\":\"host=example.com\"}]";

    private HttpServer server;
    private String base;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile String createResponse = CREATED;
    private volatile int createStatus = 201;

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /** A minimal stand-in for the shortener service, answering in the shapes of the contract. */
    private void handle(HttpExchange ex) throws java.io.IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("X-Request-Id"), body));
        int status;
        String out;
        if (ex.getRequestMethod().equals("POST") && path.equals("/api/v1/links")) { status = createStatus; out = createResponse; }
        else if (path.equals("/api/v1/links/abc1234/stats")) { status = 200; out = STATS; }
        else if (path.equals("/api/v1/links/busy0000/stats")) { status = 503; out = "{\"status\":503}"; }
        else if (path.equals("/api/v1/links/abc1234/audit") || path.equals("/api/v1/audit")) { status = 200; out = path.endsWith("/audit") && path.contains("links") ? AUDIT : "[]"; }
        else { status = 404; out = "{\"status\":404,\"detail\":\"no link\"}"; }
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status < 300 ? "application/json" : "application/problem+json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private Map<String, Tool> tools(String baseUrl) {
        return ShortenerTools.create(baseUrl, ShortenerContract.load(), Duration.ofSeconds(2)).stream()
                .collect(Collectors.toMap(t -> t.spec().name(), t -> t));
    }

    @Test
    @Verifies({"AC-11.7"})
    void toolSpecsComeFromTheContract() {
        ShortenerContract contract = ShortenerContract.load();

        ToolSpec create = contract.spec("createLink");
        assertEquals(Risk.MUTATING, create.risk(), "POST is mutating");
        assertEquals(List.of("url", "ttlSeconds"), create.params().stream().map(ToolParam::name).toList(),
                "body fields become parameters; the X-Request-Id header is the gateway's, not the planner's");
        assertTrue(create.params().get(0).required() && !create.params().get(1).required());
        assertEquals("integer", create.params().get(1).type());
        assertTrue(create.description().startsWith("Shorten a URL. Only public http(s) URLs are accepted"), create.description());

        ToolSpec stats = contract.spec("getLinkStats");
        assertEquals(Risk.READ_ONLY, stats.risk());
        assertEquals(List.of(new ToolParam("code", "string", true, "code")), stats.params(), "path parameters are required");
        ToolSpec recent = contract.spec("getRecentAudit");
        assertEquals(List.of(new ToolParam("limit", "integer", false, "limit")), recent.params());
        assertEquals(List.of("getLinkStats", "getLinkAudit", "getRecentAudit", "createLink"),
                tools(base).keySet().stream().sorted((a, b) -> order(a) - order(b)).toList());
        assertThrows(IllegalArgumentException.class, () -> contract.spec("deleteEverything"));
    }

    private static int order(String name) {
        return List.of("getLinkStats", "getLinkAudit", "getRecentAudit", "createLink").indexOf(name);
    }

    @Test
    void contractDetailsAreReadDefensively() throws Exception {
        ShortenerContract c = ShortenerContract.parse("""
                paths:
                  /x/{id}:
                    put:
                      operationId: replaceX
                      summary: Replace
                      parameters:
                        - { name: id, in: path, schema: { type: string } }
                        - { name: ratio, in: query, required: true, description: a ratio, schema: { type: number } }
                        - { name: X-Trace, in: header, schema: { type: string } }
                """.getBytes(StandardCharsets.UTF_8));
        ToolSpec s = c.spec("replaceX");
        assertEquals(Risk.MUTATING, s.risk(), "anything but GET is mutating");
        assertEquals("Replace", s.description());
        assertEquals(List.of(new ToolParam("id", "string", true, "id"), new ToolParam("ratio", "string", true, "a ratio"),
                new ToolParam("X-Trace", "string", false, "X-Trace")), s.params(), "unknown types are passed as strings; other headers stay");
    }

    @Test
    void aMissingOrUnreadableContractFailsFast(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        try (java.net.URLClassLoader empty = new java.net.URLClassLoader(new java.net.URL[0], null)) {
            assertTrue(assertThrows(IllegalStateException.class, () -> ShortenerContract.load(empty)).getMessage()
                    .contains("is not on the classpath"));
        }
        java.nio.file.Path spec = dir.resolve(ShortenerContract.LOCATION);
        java.nio.file.Files.createDirectories(spec.getParent());
        java.nio.file.Files.writeString(spec, "paths: [unclosed");
        try (java.net.URLClassLoader broken = new java.net.URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, null)) {
            assertThrows(java.io.UncheckedIOException.class, () -> ShortenerContract.load(broken));
        }
    }

    @Test
    void theRenderingFlagsABrokenAuditChain() {
        ToolGatewayDemo.Result ok = ToolGatewayDemo.run(base, ToolGatewayDemo.scriptedApprover(), "https://example.com/x");
        ToolGatewayDemo.Result tampered = new ToolGatewayDemo.Result(ok.report(), ok.audit(), false, ok.tools(), ok.output());
        assertTrue(ToolGatewayDemo.render(tampered).contains("hash chain BROKEN; outcome SUCCEEDED"));
        assertTrue(ToolGatewayDemo.render(ok).contains("RUN_COMPLETED        -"), "run-level events have no stage");
    }

    @Test
    void aBindingThatDisagreesWithTheContractIsRefused() {
        ToolSpec spec = ShortenerContract.load().spec("getLinkStats");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ShortenerTools.bind(spec, java.util.Set.of("shortCode"), new ObjectMapper(), (a, cid) -> null));
        assertEquals("binding of getLinkStats reads [shortCode] but the contract declares [code]: regenerate the client and update the binding",
                e.getMessage());
    }

    @Test
    @Verifies({"AC-11.7", "AC-11.2", "AC-11.1"})
    void anAgentCreatesAndVerifiesALinkThroughTheGatewayWithCorrelationIds() {
        ToolGatewayDemo.Result r = ToolGatewayDemo.run(base, ToolGatewayDemo.scriptedApprover(), "https://example.com/x");

        assertEquals(RunOutcome.SUCCEEDED, r.report().outcome(), r.report().stages().toString());
        assertTrue(r.auditIntact());
        assertEquals(List.of("POST /api/v1/links tools-demo.link-ops.a1.c1",
                        "GET /api/v1/links/abc1234/stats tools-demo.link-ops.a1.c2",
                        "GET /api/v1/links/abc1234/audit tools-demo.link-ops.a1.c4"),
                seen.stream().map(s -> s.method() + " " + s.path() + " " + s.requestId()).toList(),
                "the malformed call (c3) never left the gateway; every sent call carries its correlation id");
        assertTrue(seen.get(0).body().contains("\"url\":\"https://example.com/x\"") && seen.get(0).body().contains("\"ttlSeconds\":3600"),
                seen.get(0).body());
        assertTrue(r.output().contains("| getLinkStats | OK | 200 | tools-demo.link-ops.a1.c2 |"), r.output());
        assertTrue(r.output().contains("| getRecentAudit | DENIED | 0 |"));
        List<String> details = r.audit().stream().map(e -> e.type() + " " + e.detail()).toList();
        assertTrue(details.stream().anyMatch(d -> d.startsWith("APPROVAL_GRANTED tool=createLink") && d.contains("approver=release-manager")));
        assertTrue(details.contains("TOOL_CALL_DENIED tool=getRecentAudit call=tools-demo.link-ops.a1.c3 argument 'limit' is not a valid integer"));
        assertTrue(details.stream().noneMatch(d -> d.contains("https://example.com/x")), "argument values stay out of the audit log");
        assertEquals(4, r.tools().size());
    }

    @Test
    @Verifies({"AC-11.2"})
    void aRejectedCreationIsNeverSentAndTheStageFails() {
        ToolGatewayDemo.Result r = ToolGatewayDemo.run(base, ToolGatewayDemo.scriptedApprover(), "https://other.example.net/");

        assertEquals(RunOutcome.FAILED, r.report().outcome());
        assertTrue(seen.isEmpty(), "nothing reached the service");
        assertTrue(r.audit().stream().anyMatch(e -> e.type() == AuditLog.Type.APPROVAL_REJECTED
                && e.detail().contains("only example.com links may be created by agents")));
    }

    @Test
    void aCreateAnswerWithoutACodeEndsTheRunWithoutAReport() {
        createResponse = "{}";
        ToolGatewayDemo.Result r = ToolGatewayDemo.run(base, ScriptedApprovalGateway.approveAll("lead", "ok"), "https://example.com/x");
        assertEquals(RunOutcome.FAILED, r.report().outcome());
        assertEquals(1, seen.size());
    }

    @Test
    @Verifies({"AC-11.4"})
    void httpErrorsAreAnswersAndUnreachableServicesAreTransient() throws Exception {
        AuditLog audit = new AuditLog(Clock.systemUTC());
        ToolGateway.Builder b = ToolGateway.builder(audit, ScriptedApprovalGateway.silent());
        tools(base).values().forEach(t -> b.tool(t).grant("ops", t.spec().name()));
        ToolGateway g = b.build();
        ToolGateway.Context ctx = new ToolGateway.Context("r", "ops", 1);

        ToolResult missing = g.invoke(ctx, new ToolCall("getLinkStats", Map.of("code", "nope0000")));
        assertEquals(ToolResult.Status.OK, missing.status());
        assertEquals(404, missing.httpStatus());
        assertTrue(missing.content().contains("no link"));

        ToolResult busy = g.invoke(ctx, new ToolCall("getLinkStats", Map.of("code", "busy0000")));
        assertEquals("status 503 after 2 attempt(s)", busy.reason());
        assertEquals(2, seen.stream().filter(s -> s.path().contains("busy")).count(), "read-only calls are retried");

        ToolResult recent = g.invoke(ctx, new ToolCall("getRecentAudit", Map.of("limit", "5")));
        assertEquals("[]", recent.content());
        assertEquals("/api/v1/audit?limit=5", seen.get(seen.size() - 1).path());
        assertEquals(ToolResult.Status.OK, g.invoke(ctx, new ToolCall("getRecentAudit", Map.of())).status());

        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) { closedPort = s.getLocalPort(); }
        Tool unreachable = tools("http://127.0.0.1:" + closedPort).get("getLinkStats");
        assertThrows(ToolUnavailableException.class, () -> unreachable.invoke(Map.of("code", "abc1234"), "cid"));
    }

    @Test
    void createLinkMapsArgumentsAndRefusesUnformableUrlsLocally() throws Exception {
        Tool create = tools(base).get("createLink");
        ToolOutcome noTtl = create.invoke(Map.of("url", "https://example.com/y"), "cid-1");
        assertEquals(201, noTtl.status());
        assertTrue(seen.get(0).body().contains("\"ttlSeconds\":null") || !seen.get(0).body().contains("ttlSeconds"), seen.get(0).body());

        ToolOutcome bad = create.invoke(Map.of("url", "ht tp://bad url"), "cid-2");
        assertEquals(400, bad.status());
        assertEquals("{\"detail\":\"rejected before sending: IllegalArgumentException\"}", bad.body());
        assertEquals(1, seen.size(), "the malformed url was never sent");
    }

    @Test
    void anInterruptedClientCallPropagatesTheInterrupt() {
        assertThrows(InterruptedException.class, () -> ShortenerTools.outcome(() -> {
            throw new ApiException(new InterruptedException("stop"));
        }, new ObjectMapper()));
        Thread.interrupted(); // clear the flag for the next test
    }

    @Test
    void mainPrintsTheToolsTheAuditTrailAndTheReport() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            ToolGatewayDemo.main(new String[]{base});
        } finally {
            System.setOut(original);
        }
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("createLink [MUTATING] {\"type\":\"object\""), printed);
        assertTrue(printed.contains("hash chain verified intact; outcome SUCCEEDED"));
        assertTrue(printed.contains("Created `abc1234`."));
    }

    @Test
    void mainFailsLoudlyAndCanAskAHumanOnTheTerminal() {
        createStatus = 503;
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        System.setIn(new ByteArrayInputStream("y looks fine\n".getBytes(StandardCharsets.UTF_8)));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> ToolGatewayDemo.main(new String[]{"--interactive", base}));
            assertTrue(e.getMessage().startsWith("tool gateway demo did not succeed"), e.getMessage());
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
        assertEquals(1, seen.size(), "approved on the terminal, sent once, and not retried after the 503");
    }
}
