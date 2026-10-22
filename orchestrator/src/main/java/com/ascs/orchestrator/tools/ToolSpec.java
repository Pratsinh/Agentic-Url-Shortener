package com.ascs.orchestrator.tools;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a planner (an LLM or a script) is told about a tool, and what the gateway validates calls against.
 * {@link #inputSchemaJson()} is the JSON Schema an LLM tool definition expects.
 */
public record ToolSpec(String name, String description, Risk risk, List<ToolParam> params) {

    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");

    public ToolSpec {
        params = List.copyOf(params);
    }

    /** null when the arguments fit this spec; otherwise why not. Never echoes argument values. */
    public String validate(Map<String, String> args) {
        for (String name : args.keySet()) {
            if (params.stream().noneMatch(p -> p.name().equals(name))) return "unknown argument '" + name + "'";
        }
        for (ToolParam p : params) {
            String value = args.get(p.name());
            if (value == null) {
                if (p.required()) return "missing required argument '" + p.name() + "'";
                continue;
            }
            boolean badInteger = p.type().equals("integer") && !INTEGER.matcher(value).matches();
            boolean badBoolean = p.type().equals("boolean") && !value.equals("true") && !value.equals("false");
            if (badInteger || badBoolean) return "argument '" + p.name() + "' is not a valid " + p.type();
        }
        return null;
    }

    public String inputSchemaJson() {
        StringBuilder props = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (ToolParam p : params) {
            if (!props.isEmpty()) props.append(',');
            props.append(quote(p.name())).append(":{\"type\":").append(quote(p.type()))
                    .append(",\"description\":").append(quote(p.description())).append('}');
            if (p.required()) required.append(required.isEmpty() ? "" : ",").append(quote(p.name()));
        }
        return "{\"type\":\"object\",\"properties\":{" + props + "},\"required\":[" + required + "]}";
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
