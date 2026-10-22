package com.ascs.orchestrator.tools.shortener;

import com.ascs.orchestrator.tools.Risk;
import com.ascs.orchestrator.tools.ToolParam;
import com.ascs.orchestrator.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The shortener's OpenAPI document, read as data: tool names, descriptions, parameters and risk all come from the
 * contract, so what a planner is told cannot drift from what the service implements. Risk follows HTTP semantics
 * (GET is read-only, every other method mutating), which fails safe for any operation added later.
 */
public final class ShortenerContract {

    /** Classpath location inside the shortener-api jar. */
    public static final String LOCATION = "openapi/shortener-v1.yaml";
    /** Header parameters the gateway sets itself; a planner never supplies them. */
    private static final Set<String> GATEWAY_HEADERS = Set.of("X-Request-Id");
    private static final Set<String> TYPES = Set.of("string", "integer", "boolean");

    private final JsonNode root;

    private ShortenerContract(JsonNode root) {
        this.root = root;
    }

    public static ShortenerContract load() {
        return load(ShortenerContract.class.getClassLoader());
    }

    static ShortenerContract load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(LOCATION)) {
            if (in == null) throw new IllegalStateException(LOCATION + " is not on the classpath (shortener-api missing?)");
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ShortenerContract parse(byte[] yaml) throws IOException {
        return new ShortenerContract(new ObjectMapper(new YAMLFactory()).readTree(yaml));
    }

    /** The tool spec of one operation, identified by its {@code operationId}. */
    public ToolSpec spec(String operationId) {
        for (Map.Entry<String, JsonNode> path : root.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                if (!operationId.equals(op.getValue().path("operationId").asText())) continue;
                Risk risk = op.getKey().toLowerCase(Locale.ROOT).equals("get") ? Risk.READ_ONLY : Risk.MUTATING;
                String description = op.getValue().path("summary").asText()
                        + (op.getValue().has("description") ? ". " + op.getValue().path("description").asText().strip() : "");
                return new ToolSpec(operationId, description, risk, params(op.getValue()));
            }
        }
        throw new IllegalArgumentException("operation " + operationId + " is not in the shortener contract");
    }

    private List<ToolParam> params(JsonNode op) {
        List<ToolParam> out = new ArrayList<>();
        for (JsonNode p : op.path("parameters")) {
            JsonNode param = resolve(p);
            if (param.path("in").asText().equals("header") && GATEWAY_HEADERS.contains(param.path("name").asText())) continue;
            boolean required = param.path("required").asBoolean(false) || param.path("in").asText().equals("path");
            out.add(new ToolParam(param.path("name").asText(), type(param.path("schema")), required,
                    param.path("description").asText(param.path("name").asText())));
        }
        JsonNode body = resolve(op.path("requestBody").path("content").path("application/json").path("schema"));
        List<String> requiredFields = new ArrayList<>();
        body.path("required").forEach(r -> requiredFields.add(r.asText()));
        for (Map.Entry<String, JsonNode> f : body.path("properties").properties()) {
            out.add(new ToolParam(f.getKey(), type(f.getValue()), requiredFields.contains(f.getKey()),
                    f.getValue().path("description").asText(f.getKey())));
        }
        return out;
    }

    /** Follows a local {@code $ref} such as {@code #/components/parameters/Code}. */
    private JsonNode resolve(JsonNode node) {
        String ref = node.path("$ref").asText("");
        return ref.startsWith("#/") ? root.at(ref.substring(1)) : node;
    }

    private static String type(JsonNode schema) {
        String t = schema.path("type").asText("string");
        return TYPES.contains(t) ? t : "string";
    }
}
