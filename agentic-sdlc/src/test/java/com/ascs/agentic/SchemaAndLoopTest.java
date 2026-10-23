package com.ascs.agentic;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.json.JsonSchemaValidator;
import com.ascs.agentic.llm.Llm;
import com.ascs.agentic.llm.LlmException;
import com.ascs.agentic.llm.LlmLedger;
import com.ascs.agentic.llm.RecordingProvider;
import com.ascs.agentic.llm.Redactor;
import com.ascs.agentic.loop.AgentLoop;
import com.ascs.orchestrator.approval.ScriptedApprovalGateway;
import com.ascs.orchestrator.audit.AuditLog;
import com.ascs.orchestrator.model.StageInput;
import com.ascs.orchestrator.tools.Risk;
import com.ascs.orchestrator.tools.Tool;
import com.ascs.orchestrator.tools.ToolGateway;
import com.ascs.orchestrator.tools.ToolOutcome;
import com.ascs.orchestrator.tools.ToolParam;
import com.ascs.orchestrator.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Structured output validation and the governed tool-use loop, with a scripted (clearly labelled) provider. */
class SchemaAndLoopTest {

    record Answer(String verdict, int score) {}

    static final JsonNode SCHEMA = Json.parse("""
            {"type":"object","additionalProperties":false,"required":["verdict","score"],
             "properties":{"verdict":{"type":"string","enum":["OK","NO"],"minLength":2},
                           "score":{"type":"integer"}}}""");

    @Test
    void validatorReportsTypeEnumRequiredAndUnexpectedPropertiesWithoutEchoingValues() {
        assertEquals(List.of(), JsonSchemaValidator.validate(SCHEMA, Json.parse("{\"verdict\":\"OK\",\"score\":3}")));
        List<String> errors = JsonSchemaValidator.validate(SCHEMA, Json.parse("{\"verdict\":\"secret-value\",\"extra\":1}"));
        assertTrue(errors.contains("$: missing required property 'score'"), errors::toString);
        assertTrue(errors.contains("$: unexpected property 'extra'"), errors::toString);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("$.verdict: must be one of")));
        assertTrue(errors.stream().noneMatch(e -> e.contains("secret-value")), "values are not echoed");
        assertEquals(List.of("$.score: expected \"integer\" but was string"),
                JsonSchemaValidator.validate(SCHEMA, Json.parse("{\"verdict\":\"OK\",\"score\":\"3\"}")));
    }

    @Test
    void localOnlyKeywordsAreStrippedForTheApiButStillEnforcedLocally() {
        JsonNode api = JsonSchemaValidator.forApi(SCHEMA);
        assertFalse(api.toString().contains("minLength"));
        assertTrue(SCHEMA.toString().contains("minLength"), "the original is untouched");
    }

    /** Strict tool use needs closed objects with every property required; check all shipped schemas. */
    @Test
    void everyShippedSchemaIsStrictCompatible() {
        for (String s : List.of("requirement-analysis", "plan", "implementation-result", "review", "docs-result")) {
            assertStrict(Json.resource("schemas/" + s + ".schema.json"), s);
        }
    }

    private static void assertStrict(JsonNode node, String where) {
        if (node.isObject() && "object".equals(node.path("type").asText())) {
            assertFalse(node.path("additionalProperties").asBoolean(true), where + " must set additionalProperties=false");
            List<String> required = new ArrayList<>();
            node.path("required").forEach(r -> required.add(r.asText()));
            node.path("properties").fieldNames().forEachRemaining(p -> assertTrue(required.contains(p), where + "." + p + " must be required"));
        }
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) assertStrict(it.next(), where);
    }

    private static Tool echo(Risk risk) {
        ToolSpec spec = new ToolSpec("echo", "echo", risk, List.of(ToolParam.required("text", "string", "t")));
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ToolOutcome invoke(Map<String, String> args, String cid) { return new ToolOutcome(200, "echo:" + args.get("text")); }
        };
    }

    private final AuditLog audit = new AuditLog(Clock.systemUTC());
    private final ToolGateway gateway = ToolGateway.builder(audit, ScriptedApprovalGateway.silent())
            .tool(echo(Risk.READ_ONLY)).grant("s", "echo").build();
    private final StageInput in = new StageInput("run", "s", "req", List.of(), 1);
    private final AgentLoop.Terminal<Answer> terminal = new AgentLoop.Terminal<>("submit", "submit", SCHEMA, Answer.class,
            a -> a.score() < 0 ? List.of("score must not be negative") : List.of());

    @Test
    void toolCallsGoThroughTheGatewayAndAnInvalidSubmissionIsReturnedForCorrection() throws Exception {
        ScriptedProvider p = new ScriptedProvider().on("r", (spec, turn, sent) -> switch (turn) {
            case 1 -> ScriptedProvider.call("echo", Map.of("text", "hello"));
            case 2 -> ScriptedProvider.calls("submit", Map.of("verdict", "OK", "score", -1));
            default -> ScriptedProvider.call("submit", Map.of("verdict", "OK", "score", 7));
        });
        AgentLoop.Outcome<Answer> out = new AgentLoop(p, gateway, 10, 2).run(in, "r", "m", "high", 2000, "sys", "go", terminal);

        assertEquals(new Answer("OK", 7), out.value());
        assertEquals(3, out.turns());
        assertTrue(ScriptedProvider.results(p.sent.get(1)).get(0).content().contains("echo:hello"), "tool result framed for the model");
        Llm.ToolResult rejected = ScriptedProvider.results(p.sent.get(2)).get(0);
        assertTrue(rejected.isError() && rejected.content().contains("score must not be negative"));
        assertEquals(List.of("echo", "submit"), p.conversations.get(0).tools().stream().map(Llm.ToolDef::name).toList(),
                "the model sees only granted tools plus its terminal tool");
        assertTrue(p.conversations.get(0).tools().get(1).strict(), "the terminal tool is strict");
        assertEquals(1, audit.events().stream().filter(e -> e.type() == AuditLog.Type.TOOL_CALL_SUCCEEDED).count());
    }

    @Test
    void ungrantedToolsAreDeniedAndTheLoopIsBounded() {
        ScriptedProvider p = new ScriptedProvider().on("r", (spec, turn, sent) -> ScriptedProvider.call("delete_everything", Map.of()));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AgentLoop(p, gateway, 3, 2).run(in, "r", "m", "high", 2000, "sys", "go", terminal));
        assertTrue(e.getMessage().contains("did not finish within 3 model turns"));
        assertTrue(ScriptedProvider.results(p.sent.get(1)).get(0).isError());
        assertEquals(3, audit.events().stream().filter(x -> x.type() == AuditLog.Type.TOOL_CALL_DENIED).count());
    }

    @Test
    void repeatedInvalidSubmissionsFailTheAttempt() {
        ScriptedProvider p = new ScriptedProvider().on("r", (spec, turn, sent) -> ScriptedProvider.call("submit", Map.of("verdict", "MAYBE")));
        assertThrows(IllegalStateException.class, () -> new AgentLoop(p, gateway, 10, 2).run(in, "r", "m", "high", 2000, "s", "go", terminal));
    }

    @Test
    void recordingProviderAuditsEveryCallWithProviderAndRequestIdAndEnforcesTheCostCap() throws Exception {
        ScriptedProvider p = new ScriptedProvider().on("r", (spec, turn, sent) -> ScriptedProvider.text("ok"));
        LlmLedger ledger = new LlmLedger(0.0001);
        List<String> transcripts = new ArrayList<>();
        Llm.Provider rec = new RecordingProvider(p, ledger, audit, Clock.systemUTC(), new Redactor(Map.of()), (n, c) -> transcripts.add(c));
        Llm.Conversation c = rec.start(new Llm.Spec("run", "s", "r", "claude-opus-5-5", "sys", List.of(), "high", 1000));
        c.send(List.of(new Llm.Text("hello")));

        AuditLog.Event e = audit.events().stream().filter(x -> x.type() == AuditLog.Type.LLM_CALL).findFirst().orElseThrow();
        assertTrue(e.detail().contains("provider=scripted") && e.detail().contains("request_id=req_scripted_t"), e.detail());
        assertEquals(1, transcripts.size());
        assertTrue(transcripts.get(0).contains("\"provider\" : \"scripted\""));
        // scripted-model has no list price, so the cap is checked against priced calls only: add a priced one
        ledger.add(new LlmLedger.CallRecord(9, java.time.Instant.now(), "run", "s", "r", "anthropic", "claude-opus-5-5",
                "claude-opus-5-5", "req", "msg", "end_turn", new Llm.Usage(1000, 1000, 0, 0), 5, List.of(), null,
                LlmLedger.estimateCost("claude-opus-5-5", new Llm.Usage(1000, 1000, 0, 0))));
        LlmException ex = assertThrows(LlmException.class, () -> c.send(List.of(new Llm.Text("again"))));
        assertTrue(ex.getMessage().contains("cost guardrail") && !ex.retryable());
    }

    @Test
    void costEstimateUsesPublishedPricesIncludingCache() {
        assertEquals(4.0 + 20.0 + 0.2 + 5.0, LlmLedger.estimateCost("claude-opus-5-5", new Llm.Usage(1_000_000, 1_000_000, 1_000_000, 1_000_000)), 1e-9);
        assertNull(LlmLedger.estimateCost("unknown-model", new Llm.Usage(1, 1, 0, 0)));
    }

    @Test
    void redactorRemovesKeysAndCredentialEnvValues() {
        Redactor r = new Redactor(Map.of("ANTHROPIC_API_KEY", "sk-ant-api03-abcdefghijklmnop", "PATH", "/usr/bin:/bin"));
        // fixtures assembled from fragments so the repository's own code-review agent does not count them as leaks
        String out = r.redact("key sk-ant-api03-abcdefghijklmnop and " + "AKIA" + "ABCDEFGHIJKLMNOP and password=\"hunter22secret\" /usr/bin:/bin");
        assertFalse(out.contains("abcdefghijklmnop") || out.contains("AKIAABCD") || out.contains("hunter22secret"), out);
        assertTrue(out.contains("/usr/bin:/bin"), "non-secret env values are kept");
    }
}
