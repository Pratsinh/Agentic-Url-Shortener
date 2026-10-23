package com.ascs.agentic;

import com.ascs.agentic.json.Json;
import com.ascs.agentic.llm.Llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEST DOUBLE. Plays back scripted model turns per agent role so governance can be tested offline. Its name is
 * "scripted", which every call record and audit event carries: a scripted run can never pass for a real model run.
 */
public final class ScriptedProvider implements Llm.Provider {

    @FunctionalInterface
    public interface Script {
        /** @param turn 1-based turn number within this conversation */
        Llm.Turn next(Llm.Spec spec, int turn, List<Llm.UserBlock> sent);
    }

    private final Map<String, Script> byRole = new ConcurrentHashMap<>();
    public final List<Llm.Spec> conversations = new CopyOnWriteArrayList<>();
    public final List<List<Llm.UserBlock>> sent = new CopyOnWriteArrayList<>();
    private static final AtomicInteger IDS = new AtomicInteger();

    public ScriptedProvider on(String role, Script s) {
        byRole.put(role, s);
        return this;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public Llm.Conversation start(Llm.Spec spec) {
        conversations.add(spec);
        Script s = byRole.get(spec.role());
        if (s == null) throw new IllegalStateException("no script for role " + spec.role());
        AtomicInteger turn = new AtomicInteger();
        return blocks -> {
            sent.add(List.copyOf(blocks));
            return s.next(spec, turn.incrementAndGet(), blocks);
        };
    }

    public static Llm.Turn calls(Object... nameInputPairs) {
        List<Llm.ToolUse> uses = new ArrayList<>();
        for (int i = 0; i < nameInputPairs.length; i += 2) {
            uses.add(new Llm.ToolUse("tu_" + IDS.incrementAndGet(), (String) nameInputPairs[i], Json.MAPPER.valueToTree(nameInputPairs[i + 1])));
        }
        return new Llm.Turn("", uses, "tool_use", new Llm.Usage(100, 20, 0, 0), "scripted-model", "req_scripted_" + IDS.get(), "msg");
    }

    public static Llm.Turn call(String name, Object input) {
        return calls(name, input);
    }

    public static Llm.Turn text(String text) {
        return new Llm.Turn(text, List.of(), "end_turn", new Llm.Usage(50, 10, 0, 0), "scripted-model", "req_scripted_t", "msg");
    }

    /** The content of the tool results the agent sent back in its last message. */
    public static List<Llm.ToolResult> results(List<Llm.UserBlock> sent) {
        return sent.stream().filter(b -> b instanceof Llm.ToolResult).map(b -> (Llm.ToolResult) b).toList();
    }
}
