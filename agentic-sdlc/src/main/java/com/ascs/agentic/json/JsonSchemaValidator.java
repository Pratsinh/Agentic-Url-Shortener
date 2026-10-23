package com.ascs.agentic.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates model output against the JSON Schema subset this module's schemas use: type (incl. arrays of types),
 * enum, properties, required, additionalProperties=false, items, min/maxItems, min/maxLength and pattern.
 *
 * <p>Strict tool use constrains the model to the schema's structure, but the API does not enforce every keyword
 * (lengths, counts, patterns), and a scripted or misbehaving provider is constrained by nothing. Every structured
 * output is therefore checked here, in full, before it is bound to a Java type.
 */
public final class JsonSchemaValidator {

    private static final int MAX_ERRORS = 20;
    /** Keywords enforced locally but not sent to the API's strict mode. */
    private static final Set<String> LOCAL_ONLY = Set.of("minLength", "maxLength", "minItems", "maxItems", "pattern");

    private JsonSchemaValidator() {}

    /** @return human-readable violations (empty when valid); values are never echoed, only paths and rules */
    public static List<String> validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        check(schema, value, "$", errors);
        return errors;
    }

    /** A copy of {@code schema} without the keywords that are only enforced locally. */
    public static JsonNode forApi(JsonNode schema) {
        JsonNode copy = schema.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode o) {
            o.remove(LOCAL_ONLY);
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) strip(it.next().getValue());
        } else if (node instanceof ArrayNode a) {
            a.forEach(JsonSchemaValidator::strip);
        }
    }

    private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (errors.size() >= MAX_ERRORS) return;
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            errors.add(path + ": expected " + type + " but was " + typeOf(value));
            return;
        }
        JsonNode en = schema.get("enum");
        if (en != null) {
            boolean found = false;
            for (JsonNode option : en) found |= option.equals(value);
            if (!found) errors.add(path + ": must be one of " + en);
        }
        if (value.isTextual()) checkString(schema, value.asText(), path, errors);
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errors.add(path + ": fewer than " + schema.get("minItems").asInt() + " items");
            }
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                errors.add(path + ": more than " + schema.get("maxItems").asInt() + " items");
            }
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int i = 0; i < value.size(); i++) check(items, value.get(i), path + "[" + i + "]", errors);
            }
        }
        if (value.isObject()) checkObject(schema, value, path, errors);
    }

    private static void checkString(JsonNode schema, String s, String path, List<String> errors) {
        if (schema.has("minLength") && s.length() < schema.get("minLength").asInt()) {
            errors.add(path + ": shorter than " + schema.get("minLength").asInt() + " characters");
        }
        if (schema.has("maxLength") && s.length() > schema.get("maxLength").asInt()) {
            errors.add(path + ": longer than " + schema.get("maxLength").asInt() + " characters");
        }
        if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(s).find()) {
            errors.add(path + ": does not match pattern " + schema.get("pattern").asText());
        }
    }

    private static void checkObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        if (required != null) {
            for (JsonNode r : required) {
                if (!value.has(r.asText())) errors.add(path + ": missing required property '" + r.asText() + "'");
            }
        }
        boolean closed = schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean(true);
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            JsonNode propSchema = props == null ? null : props.get(f.getKey());
            if (propSchema != null) {
                check(propSchema, f.getValue(), path + "." + f.getKey(), errors);
            } else if (closed) {
                errors.add(path + ": unexpected property '" + f.getKey() + "'");
            }
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode t : type) if (typeMatches(t, value)) return true;
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static String typeOf(JsonNode v) {
        return v.isObject() ? "object" : v.isArray() ? "array" : v.isTextual() ? "string" : v.isIntegralNumber() ? "integer"
                : v.isNumber() ? "number" : v.isBoolean() ? "boolean" : "null";
    }
}
