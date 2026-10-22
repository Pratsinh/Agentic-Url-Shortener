package com.ascs.orchestrator.tools;

import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/** A tool call proposed by a planner. Nothing happens until the {@link ToolGateway} accepts it. */
public record ToolCall(String tool, Map<String, String> args) {

    public ToolCall {
        args = Map.copyOf(args);
    }

    /** {@code name(k=v, ...)} for a human approver; never written to the audit log. */
    public String render() {
        StringJoiner sj = new StringJoiner(", ", tool + "(", ")");
        new TreeMap<>(args).forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }
}
