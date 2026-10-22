package com.ascs.orchestrator.tools;

import java.util.Map;

/**
 * A capability of an external system, invoked only through the {@link ToolGateway}. Implementations do the I/O and
 * nothing else: no policy, approval or retry logic (the gateway owns those).
 */
public interface Tool {

    ToolSpec spec();

    /**
     * @param args          validated against {@link #spec()} by the gateway
     * @param correlationId to send to the target system (e.g. as X-Request-Id) so both sides' logs can be joined
     * @return the response; a 5xx status counts as a transient failure
     * @throws ToolUnavailableException when the system could not be reached (transient)
     */
    ToolOutcome invoke(Map<String, String> args, String correlationId) throws Exception;
}
