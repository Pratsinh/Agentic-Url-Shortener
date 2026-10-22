package com.ascs.orchestrator.tools.shortener;

import com.ascs.contract.shortener.ApiClient;
import com.ascs.contract.shortener.ApiException;
import com.ascs.contract.shortener.ApiResponse;
import com.ascs.contract.shortener.api.AuditApi;
import com.ascs.contract.shortener.api.LinksApi;
import com.ascs.contract.shortener.model.CreateLinkRequest;
import com.ascs.orchestrator.tools.Tool;
import com.ascs.orchestrator.tools.ToolOutcome;
import com.ascs.orchestrator.tools.ToolParam;
import com.ascs.orchestrator.tools.ToolSpec;
import com.ascs.orchestrator.tools.ToolUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The curated set of shortener operations exposed as tools: stats and audit reads, and link creation (mutating, so
 * it always needs a human approval in the gateway). Each tool's spec comes from the contract; the binding to the
 * generated client is checked against it at construction, so a renamed or added parameter fails fast instead of
 * being silently dropped. The redirect and the contract download are deliberately not tools.
 */
public final class ShortenerTools {
    private ShortenerTools() {}

    /** Calls the generated client; argument values are already validated against the spec by the gateway. */
    @FunctionalInterface
    interface Binding {
        ApiResponse<?> call(Map<String, String> args, String correlationId) throws ApiException;
    }

    /** All curated tools against the service at {@code baseUri}. */
    public static List<Tool> create(String baseUri, ShortenerContract contract, Duration timeout) {
        ApiClient client = new ApiClient(HttpClient.newBuilder().connectTimeout(timeout), new ApiClient().getObjectMapper(), baseUri)
                .setReadTimeout(timeout);
        LinksApi links = new LinksApi(client);
        AuditApi audit = new AuditApi(client);
        ObjectMapper json = client.getObjectMapper();
        return List.of(
                bind(contract.spec("getLinkStats"), Set.of("code"), json,
                        (a, cid) -> links.getLinkStatsWithHttpInfo(a.get("code"), cid)),
                bind(contract.spec("getLinkAudit"), Set.of("code"), json,
                        (a, cid) -> audit.getLinkAuditWithHttpInfo(a.get("code"), cid)),
                bind(contract.spec("getRecentAudit"), Set.of("limit"), json,
                        (a, cid) -> audit.getRecentAuditWithHttpInfo(a.containsKey("limit") ? Integer.valueOf(a.get("limit")) : null, cid)),
                bind(contract.spec("createLink"), Set.of("url", "ttlSeconds"), json,
                        (a, cid) -> links.createLinkWithHttpInfo(new CreateLinkRequest().url(URI.create(a.get("url")))
                                .ttlSeconds(a.containsKey("ttlSeconds") ? Long.valueOf(a.get("ttlSeconds")) : null), cid)));
    }

    static Tool bind(ToolSpec spec, Set<String> reads, ObjectMapper json, Binding binding) {
        Set<String> declared = new TreeSet<>(spec.params().stream().map(ToolParam::name).toList());
        if (!declared.equals(new TreeSet<>(reads))) {
            throw new IllegalStateException("binding of " + spec.name() + " reads " + new TreeSet<>(reads)
                    + " but the contract declares " + declared + ": regenerate the client and update the binding");
        }
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }

            @Override
            public ToolOutcome invoke(Map<String, String> args, String correlationId) throws Exception {
                return outcome(() -> binding.call(args, correlationId), json);
            }
        };
    }

    /** Client call -> tool outcome: HTTP errors are answers, I/O failures are transient, interrupts propagate. */
    static ToolOutcome outcome(ClientCall call, ObjectMapper json) throws Exception {
        try {
            ApiResponse<?> r = call.run();
            return new ToolOutcome(r.getStatusCode(), json.writeValueAsString(r.getData()));
        } catch (IllegalArgumentException e) {
            // e.g. a url the client cannot even form a request with: answered like the service would, nothing is sent
            return new ToolOutcome(400, "{\"detail\":\"rejected before sending: " + e.getClass().getSimpleName() + "\"}");
        } catch (ApiException e) {
            if (e.getCause() instanceof InterruptedException) throw new InterruptedException("interrupted while calling the shortener");
            if (e.getCause() instanceof IOException io) throw new ToolUnavailableException(io.getClass().getSimpleName(), io);
            return new ToolOutcome(e.getCode(), e.getResponseBody());
        }
    }

    /** A call of the generated client. */
    @FunctionalInterface
    interface ClientCall {
        ApiResponse<?> run() throws ApiException;
    }
}
