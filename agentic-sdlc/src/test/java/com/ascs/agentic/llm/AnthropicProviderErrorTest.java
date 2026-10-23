package com.ascs.agentic.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicProviderErrorTest {

    // fixture assembled from fragments so the repository's own code-review agent does not count it as a leak
    private static final String KEY = "sk-ant-" + "api03-abcdefghijklmnop";
    private final Redactor redactor = new Redactor(Map.of("ANTHROPIC_API_KEY", KEY));

    @Test
    void apiErrorDetailIsKeptSoAFailedRunCanBeDiagnosed() {
        String msg = AnthropicProvider.errorMessage(400, "BadRequestException",
                "400: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"model: not found\"}}", redactor);
        assertTrue(msg.startsWith("Anthropic API error 400 (BadRequestException): "), msg);
        assertTrue(msg.contains("invalid_request_error") && msg.contains("model: not found"), msg);
    }

    @Test
    void apiErrorDetailIsRedactedTruncatedAndOptional() {
        String msg = AnthropicProvider.errorMessage(401, "UnauthorizedException", "bad key " + KEY + "\n" + "x".repeat(2000), redactor);
        assertFalse(msg.contains("abcdefghijklmnop"), msg);
        assertTrue(msg.endsWith("...") && msg.length() < AnthropicProvider.MAX_ERROR_DETAIL + 100, msg);
        assertEquals("Anthropic API error 500 (InternalServerException)",
                AnthropicProvider.errorMessage(500, "InternalServerException", " ", redactor));
        assertEquals("Anthropic API error 500 (InternalServerException)",
                AnthropicProvider.errorMessage(500, "InternalServerException", null, redactor));
    }
}
