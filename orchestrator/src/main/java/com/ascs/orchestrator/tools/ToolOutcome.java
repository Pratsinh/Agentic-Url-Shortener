package com.ascs.orchestrator.tools;

/** Raw result of one tool invocation: an HTTP-style status and the response body. */
public record ToolOutcome(int status, String body) {
}
