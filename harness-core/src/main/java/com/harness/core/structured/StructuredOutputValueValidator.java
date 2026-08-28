package com.harness.core.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.StructuredOutputException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses and validates a structured value against the accepted schema subset. */
public final class StructuredOutputValueValidator {

    private static final int MAX_VIOLATIONS = 20;
    private final ObjectMapper objectMapper;

    public StructuredOutputValueValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode parseAndValidate(String output, JsonNode schema) {
        if (output == null || output.isBlank()) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_EMPTY,
                    "Model returned an empty structured output");
        }

        JsonNode value;
        try {
            value = objectMapper.readTree(output);
        } catch (JsonProcessingException e) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_INVALID_JSON,
                    "Model returned invalid JSON",
                    Map.of("location", e.getLocation() != null
                            ? e.getLocation().getCharOffset() : -1L), e);
        }
        return validate(value, schema);
    }

    public JsonNode validate(JsonNode value, JsonNode schema) {
        if (value == null || value.isNull()) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_EMPTY,
                    "Structured output is empty");
        }
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("schema must be a JSON object");
        }

        List<String> violations = new ArrayList<>();
        validateNode(value, schema, "$", violations);
        if (!violations.isEmpty()) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_SCHEMA_MISMATCH,
                    "Structured output does not match the requested schema",
                    Map.of("violations", List.copyOf(violations)));
        }
        return value.deepCopy();
    }

    private void validateNode(
            JsonNode value, JsonNode schema, String path, List<String> violations) {
        if (violations.size() >= MAX_VIOLATIONS) {
            return;
        }
        String type = schema.path("type").asText();
        boolean typeMatches = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
        if (!typeMatches) {
            violations.add(path + ": expected " + type + ", got " + value.getNodeType());
            return;
        }

        JsonNode enumNode = schema.get("enum");
        if (enumNode != null) {
            boolean found = false;
            for (JsonNode allowed : enumNode) {
                if (allowed.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                violations.add(path + ": value is not in enum");
            }
        }

        if (value.isObject()) {
            validateObject(value, schema, path, violations);
        } else if (value.isArray()) {
            int index = 0;
            for (JsonNode item : value) {
                validateNode(item, schema.get("items"), path + "/" + index, violations);
                index++;
            }
        }
    }

    private void validateObject(
            JsonNode value, JsonNode schema, String path, List<String> violations) {
        JsonNode properties = schema.get("properties");
        Set<String> required = new HashSet<>();
        schema.get("required").forEach(node -> required.add(node.asText()));
        for (String name : required) {
            if (!value.has(name)) {
                violations.add(path + "/" + name + ": required property is missing");
            }
        }

        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext() && violations.size() < MAX_VIOLATIONS) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propertySchema = properties.get(field.getKey());
            if (propertySchema == null) {
                if (!schema.path("additionalProperties").asBoolean(true)) {
                    violations.add(path + "/" + field.getKey()
                            + ": additional property is not allowed");
                }
                continue;
            }
            validateNode(field.getValue(), propertySchema,
                    path + "/" + field.getKey(), violations);
        }
    }
}
