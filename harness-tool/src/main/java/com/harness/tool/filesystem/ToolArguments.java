package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;

/** Argument reading and JSON Schema building shared by the code tools. */
final class ToolArguments {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArguments() {
    }

    static String requiredText(String toolName, JsonNode arguments, String name) {
        if (arguments == null || !arguments.isObject()) {
            throw new ToolExecutionException(toolName, "arguments must be a JSON object");
        }
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new ToolExecutionException(toolName, "Missing required parameter: " + name);
        }
        return value.asText();
    }

    /** Like {@link #requiredText} but an empty string is a legitimate value (deleting a block). */
    static String requiredTextAllowingEmpty(String toolName, JsonNode arguments, String name) {
        if (arguments == null || !arguments.isObject()) {
            throw new ToolExecutionException(toolName, "arguments must be a JSON object");
        }
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            throw new ToolExecutionException(toolName, "Missing required parameter: " + name);
        }
        return value.asText();
    }

    static String optionalText(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    static int optionalInt(String toolName, JsonNode arguments, String name, int defaultValue) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw new ToolExecutionException(toolName, name + " must be an integer");
        }
        return value.asInt();
    }

    static boolean optionalBool(JsonNode arguments, String name, boolean defaultValue) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value == null || value.isNull() ? defaultValue : value.asBoolean(defaultValue);
    }

    static ObjectNode objectSchema() {
        return MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
    }

    static ObjectNode stringProperty(ObjectNode schema, String name, String description) {
        schema.withObject("/properties").putObject(name).put("type", "string").put("description", description);
        return schema;
    }

    static ObjectNode intProperty(ObjectNode schema, String name, String description) {
        schema.withObject("/properties").putObject(name).put("type", "integer").put("description", description);
        return schema;
    }

    static ObjectNode boolProperty(ObjectNode schema, String name, String description) {
        schema.withObject("/properties").putObject(name).put("type", "boolean").put("description", description);
        return schema;
    }

    static ObjectNode required(ObjectNode schema, String... names) {
        ArrayNode array = schema.putArray("required");
        for (String name : names) {
            array.add(name);
        }
        return schema;
    }
}
