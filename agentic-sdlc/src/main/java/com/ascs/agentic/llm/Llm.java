package com.ascs.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral contract between the agents and a language model. Agents depend only on these types, so the
 * Anthropic adapter can be swapped (or scripted in tests) without touching agent code.
 *
 * <p>A {@link Conversation} owns its message history: providers must replay their own responses verbatim on the next
 * turn (for Claude, thinking blocks are bound to the conversation and an edited history is rejected), so callers
 * only ever append user content.
 */
public final class Llm {
    private Llm() {}

    /** Starts conversations; one per agent attempt. */
    public interface Provider {
        /** Short identifier written to every call record (e.g. {@code anthropic}, {@code scripted}). */
        String name();

        Conversation start(Spec spec);
    }

    /** A stateful multi-turn exchange. Not thread-safe: one agent attempt drives it. */
    public interface Conversation {
        Turn send(List<UserBlock> blocks) throws LlmException, InterruptedException;
    }

    /**
     * Fixed per conversation, so the prompt prefix (tools, system) stays byte-identical and cacheable.
     *
     * @param role   agent role, e.g. {@code requirements-analyst}; recorded with every call
     * @param effort {@code low|medium|high|xhigh|max}
     */
    public record Spec(String runId, String stageId, String role, String model, String system, List<ToolDef> tools,
                       String effort, int maxTokens) {
        public Spec {
            Objects.requireNonNull(model);
            tools = List.copyOf(tools);
        }
    }

    /** A tool the model may call. {@code strict} asks the API to guarantee arguments that match the schema. */
    public record ToolDef(String name, String description, JsonNode inputSchema, boolean strict) {}

    /** Content of one user turn. */
    public sealed interface UserBlock permits Text, ToolResult {}

    public record Text(String text) implements UserBlock {}

    public record ToolResult(String toolUseId, String content, boolean isError) implements UserBlock {}

    public record ToolUse(String id, String name, JsonNode input) {}

    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        public static final Usage ZERO = new Usage(0, 0, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                    cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens);
        }
    }

    /**
     * One model response.
     *
     * @param model     model that actually answered (may differ from the requested one after a refusal fallback)
     * @param requestId provider request id (Anthropic {@code request-id} header): proof of a real call, and the key
     *                  for provider-side support/debugging
     */
    public record Turn(String text, List<ToolUse> toolUses, String stopReason, Usage usage, String model,
                       String requestId, String messageId) {
        public Turn {
            toolUses = List.copyOf(toolUses);
        }
    }
}
