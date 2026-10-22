package com.ascs.orchestrator.tools;

/**
 * One argument of a tool.
 *
 * @param type JSON Schema type: {@code string}, {@code integer} or {@code boolean}
 */
public record ToolParam(String name, String type, boolean required, String description) {

    public static ToolParam required(String name, String type, String description) {
        return new ToolParam(name, type, true, description);
    }

    public static ToolParam optional(String name, String type, String description) {
        return new ToolParam(name, type, false, description);
    }
}
