package com.ascs.agentic.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Claude via the official Anthropic Java SDK (Messages API, client-side tool use).
 *
 * <ul>
 *   <li>Credentials: {@code AnthropicOkHttpClient.builder().fromEnv()} (ANTHROPIC_API_KEY etc.); never logged.</li>
 *   <li>Transient failures (429, 5xx/overloaded, connection errors) are retried by the SDK with backoff,
 *       bounded by {@code maxRetries}; what is left surfaces as {@link LlmException}.</li>
 *   <li>Thinking stays adaptive (the model default) and depth is set with {@code output_config.effort}. Forced
 *       {@code tool_choice} is not used (current models reject it): agents finish through a strict "submit" tool.</li>
 *   <li>The full assistant message is appended back unchanged ({@link Message#toParam()}), so thinking blocks and
 *       tool-use ids survive and the history is append-only.</li>
 *   <li>Prompt caching: the system prompt and tools are a stable prefix; top-level automatic caching covers the
 *       growing tool-loop history.</li>
 *   <li>Refusal fallback (on by default, {@code AGENTIC_REFUSAL_FALLBACK=off} disables): the server re-routes a
 *       request a safety classifier declines; the answering model is recorded per call.</li>
 * </ul>
 */
public final class AnthropicProvider implements Llm.Provider {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    /** Longest API error detail kept in an exception message (and so in the ledger, audit log and run summary). */
    static final int MAX_ERROR_DETAIL = 500;

    private final AnthropicClient client;
    private final boolean refusalFallback;
    private final Redactor redactor = Redactor.fromProcessEnv();

    public AnthropicProvider(AnthropicClient client, boolean refusalFallback) {
        this.client = client;
        this.refusalFallback = refusalFallback;
    }

    /**
     * The message for a failed API call: status, exception type and the API's own error detail (e.g. which request
     * field a 400 rejected), redacted and truncated, so a failed run can be diagnosed from its summary alone.
     */
    static String errorMessage(int status, String type, String detail, Redactor redactor) {
        String msg = "Anthropic API error " + status + " (" + type + ")";
        if (detail == null || detail.isBlank()) return msg;
        String clean = redactor.redact(detail.strip().replaceAll("\\s+", " "));
        if (clean.length() > MAX_ERROR_DETAIL) clean = clean.substring(0, MAX_ERROR_DETAIL) + "...";
        return msg + ": " + clean;
    }

    public static AnthropicProvider fromEnv(boolean refusalFallback) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .fromEnv()
                .maxRetries(4)
                .timeout(Duration.ofMinutes(10))
                .build();
        return new AnthropicProvider(client, refusalFallback);
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public Llm.Conversation start(Llm.Spec spec) {
        return new AnthropicConversation(spec);
    }

    /** Builds the request for the given history; package-private so tests can inspect it without a network call. */
    MessageCreateParams params(Llm.Spec spec, List<MessageParam> history) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(spec.model())
                .maxTokens(spec.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(spec.system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .messages(history)
                .cacheControl(CacheControlEphemeral.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(spec.effort())).build());
        for (Llm.ToolDef t : spec.tools()) b.addTool(toSdkTool(t));
        if (refusalFallback) {
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b.build();
    }

    static Tool toSdkTool(Llm.ToolDef def) {
        JsonNode schema = def.inputSchema();
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        JsonNode p = schema.get("properties");
        if (p != null) {
            for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                props.putAdditionalProperty(f.getKey(), JsonValue.fromJsonNode(f.getValue()));
            }
        }
        List<String> required = new ArrayList<>();
        if (schema.has("required")) schema.get("required").forEach(r -> required.add(r.asText()));
        Tool.InputSchema.Builder input = Tool.InputSchema.builder().properties(props.build()).required(required);
        if (schema.has("additionalProperties")) {
            input.putAdditionalProperty("additionalProperties", JsonValue.fromJsonNode(schema.get("additionalProperties")));
        }
        Tool.Builder tool = Tool.builder().name(def.name()).description(def.description()).inputSchema(input.build());
        if (def.strict()) tool.strict(true);
        return tool.build();
    }

    private final class AnthropicConversation implements Llm.Conversation {
        private final Llm.Spec spec;
        private final List<MessageParam> history = new ArrayList<>();

        AnthropicConversation(Llm.Spec spec) {
            this.spec = spec;
        }

        @Override
        public Llm.Turn send(List<Llm.UserBlock> blocks) throws LlmException, InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException("cancelled before model call");
            history.add(userMessage(blocks));
            HttpResponseFor<Message> raw;
            Message m;
            try {
                raw = client.messages().withRawResponse().create(params(spec, history));
                m = raw.parse();
            } catch (AnthropicServiceException e) {
                history.remove(history.size() - 1); // the model never saw it: keep the history consistent
                int status = e.statusCode();
                throw new LlmException(errorMessage(status, e.getClass().getSimpleName(), e.getMessage(), redactor),
                        status == 408 || status == 409 || status == 429 || status >= 500, e);
            } catch (AnthropicIoException e) {
                history.remove(history.size() - 1);
                throw new LlmException("Anthropic API unreachable: " + e.getMessage(), true, e);
            }
            history.add(m.toParam());

            StringBuilder text = new StringBuilder();
            List<Llm.ToolUse> uses = new ArrayList<>();
            for (ContentBlock block : m.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
                block.toolUse().ifPresent(t -> uses.add(new Llm.ToolUse(t.id(), t.name(), t._input().convert(JsonNode.class))));
            }
            String stop = m.stopReason().map(Object::toString).orElse("unknown");
            if ("refusal".equals(stop)) throw new LlmException("the model declined the request (stop_reason=refusal)", false);
            var u = m.usage();
            return new Llm.Turn(text.toString(), uses, stop,
                    new Llm.Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L),
                            u.cacheCreationInputTokens().orElse(0L)),
                    m.model().toString(), raw.requestId().orElse(""), m.id());
        }

        private MessageParam userMessage(List<Llm.UserBlock> blocks) {
            List<ContentBlockParam> content = new ArrayList<>();
            for (Llm.UserBlock b : blocks) {
                switch (b) {
                    case Llm.Text t -> content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build()));
                    case Llm.ToolResult r -> content.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(r.toolUseId()).content(r.content()).isError(r.isError()).build()));
                }
            }
            return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(content).build();
        }
    }
}
