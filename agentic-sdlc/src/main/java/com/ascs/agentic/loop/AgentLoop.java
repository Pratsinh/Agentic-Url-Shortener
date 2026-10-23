package com.ascs.agentic.loop;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.json.JsonSchemaValidator;
import com.ascs.agentic.llm.Llm;
import com.ascs.agentic.llm.LlmException;
import com.ascs.orchestrator.model.StageInput;
import com.ascs.orchestrator.tools.ToolCall;
import com.ascs.orchestrator.tools.ToolGateway;
import com.ascs.orchestrator.tools.ToolResult;
import com.ascs.orchestrator.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The agentic tool-use loop shared by every LLM agent.
 *
 * <p>The model sees the tools granted to its stage plus one <em>terminal</em> tool (strict schema). Each tool call
 * goes through the {@link ToolGateway} (grants, argument validation, policies, approval, audit); the model only
 * proposes. The loop ends when the model submits a terminal result that passes the JSON schema, binds to the
 * result type and passes the agent's semantic checks; an invalid submission is returned to the model as an error
 * so it can correct itself, a bounded number of times. Turns are bounded too: a model that never finishes fails the
 * stage attempt and the engine's retry/fallback policy takes over.
 */
public final class AgentLoop {

    /** How an agent finishes: the schema the model must satisfy and the agent-specific checks on top. */
    public record Terminal<T>(String name, String description, JsonNode schema, Class<T> type,
                              Function<T, List<String>> semanticCheck) {}

    /** What the loop produced, with the tool results it saw (for evidence) and the turn count. */
    public record Outcome<T>(T value, JsonNode raw, int turns, List<ToolResult> toolResults) {}

    private final Llm.Provider provider;
    private final ToolGateway gateway;
    private final int maxTurns;
    private final int maxInvalidSubmissions;

    public AgentLoop(Llm.Provider provider, ToolGateway gateway, int maxTurns, int maxInvalidSubmissions) {
        this.provider = provider;
        this.gateway = gateway;
        this.maxTurns = maxTurns;
        this.maxInvalidSubmissions = maxInvalidSubmissions;
    }

    public <T> Outcome<T> run(StageInput in, String role, String model, String effort, int maxTokens, String system,
                              String userPrompt, Terminal<T> terminal) throws LlmException, InterruptedException {
        List<ToolSpec> granted = gateway.toolsFor(in.stageId());
        List<Llm.ToolDef> defs = new ArrayList<>();
        for (ToolSpec s : granted) defs.add(new Llm.ToolDef(s.name(), s.description(), Json.parse(s.inputSchemaJson()), false));
        defs.add(new Llm.ToolDef(terminal.name(), terminal.description(), JsonSchemaValidator.forApi(terminal.schema()), true));

        Llm.Conversation conv = provider.start(new Llm.Spec(in.runId(), in.stageId(), role, model, system, defs, effort, maxTokens));
        ToolGateway.Context ctx = new ToolGateway.Context(in.runId(), in.stageId(), in.attempt());
        List<ToolResult> results = new ArrayList<>();
        List<Llm.UserBlock> next = List.of(new Llm.Text(userPrompt));
        int invalid = 0;

        for (int turn = 1; turn <= maxTurns; turn++) {
            Llm.Turn t = conv.send(next);
            List<Llm.UserBlock> replies = new ArrayList<>();
            if (t.toolUses().isEmpty()) {
                replies.add(new Llm.Text("max_tokens".equals(t.stopReason())
                        ? "Your response hit the output limit before a tool call completed. Work in smaller steps "
                          + "(e.g. replace_in_file instead of rewriting whole files)."
                        : "Continue with tool calls. When the work is complete, call `" + terminal.name() + "`."));
                next = replies;
                continue;
            }
            Outcome<T> done = null;
            for (Llm.ToolUse use : t.toolUses()) {
                if (use.name().equals(terminal.name())) {
                    if (done != null) {
                        replies.add(new Llm.ToolResult(use.id(), "ignored: a result was already accepted", true));
                        continue;
                    }
                    List<String> errors = check(terminal, use.input());
                    if (errors.isEmpty()) {
                        done = new Outcome<>(Json.convert(use.input(), terminal.type()), use.input(), turn, List.copyOf(results));
                        replies.add(new Llm.ToolResult(use.id(), "accepted", false));
                    } else {
                        if (++invalid > maxInvalidSubmissions) {
                            throw new IllegalStateException(terminal.name() + " rejected " + invalid + " times; last errors: " + errors);
                        }
                        replies.add(new Llm.ToolResult(use.id(), "Rejected. Fix these problems and call " + terminal.name()
                                + " again:\n- " + String.join("\n- ", errors), true));
                    }
                } else {
                    ToolResult r = gateway.invoke(ctx, new ToolCall(use.name(), toArgs(use.input())));
                    results.add(r);
                    boolean error = r.status() != ToolResult.Status.OK || r.httpStatus() >= 400;
                    replies.add(new Llm.ToolResult(use.id(), r.forModel(), error));
                }
            }
            if (done != null) return done;
            next = replies;
        }
        throw new IllegalStateException(role + " did not finish within " + maxTurns + " model turns");
    }

    private static <T> List<String> check(Terminal<T> terminal, JsonNode input) {
        List<String> errors = JsonSchemaValidator.validate(terminal.schema(), input);
        if (!errors.isEmpty()) return errors;
        T value;
        try {
            value = Json.convert(input, terminal.type());
        } catch (IllegalArgumentException e) {
            return List.of("result does not bind to " + terminal.type().getSimpleName() + ": " + e.getMessage());
        }
        return terminal.semanticCheck().apply(value);
    }

    /** Gateway tools take string arguments: text stays as is, numbers and booleans are printed, the rest is JSON. */
    static Map<String, String> toArgs(JsonNode input) {
        Map<String, String> args = new LinkedHashMap<>();
        if (input == null || !input.isObject()) return args;
        for (Iterator<Map.Entry<String, JsonNode>> it = input.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            JsonNode v = f.getValue();
            if (v.isNull()) continue;
            args.put(f.getKey(), v.isTextual() ? v.asText() : v.isValueNode() ? v.asText() : v.toString());
        }
        return args;
    }
}
