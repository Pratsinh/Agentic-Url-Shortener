package com.ascs.agentic.llm;

/** A model call that did not produce a usable response (after the provider's own bounded retries). */
public class LlmException extends Exception {

    private final boolean retryable;

    public LlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public LlmException(String message, boolean retryable) {
        this(message, retryable, null);
    }

    /** True for transient conditions (rate limit, overload, network): a later stage attempt may succeed. */
    public boolean retryable() {
        return retryable;
    }
}
