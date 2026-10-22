package com.ascs.orchestrator.tools;

/** The target system could not be reached or timed out: a transient failure that a read-only call may retry. */
public class ToolUnavailableException extends Exception {
    public ToolUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
