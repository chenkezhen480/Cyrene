package com.harness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.StructuredOutputException;
import com.harness.core.model.FinalOutputContract;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the bounded JSON Schema subset supported by structured output providers. */
public final class StructuredOutputSchemaValidator {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> SUPPORTED_KEYWORDS = Set.of(
            "type", "description", "properties", "required",
            "additionalProperties", "items", "enum");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean");

    public record Limits(
            int maxBytes,
            int maxDepth,
            int maxProperties,
            int maxEnumValues
    ) {
        public Limits {
            if (maxBytes <= 0 || maxDepth <= 0
                    || maxProperties <= 0 || maxEnumValues <= 0) {
                throw new IllegalArgumentException("Schema limits must be positive");
            }
        }

        static Limits fromEnvironment() {
            EnvConfig config = EnvConfig.get();
            return new Limits(
                    config.getInt(EnvKey.STRUCTURED_SCHEMA_MAX_BYTES, 65_536),
                    config.getInt(EnvKey.STRUCTURED_SCHEMA_MAX_DEPTH, 12),
                    config.getInt(EnvKey.STRUCTURED_SCHEMA_MAX_PROPERTIES, 200),
                    config.getInt(EnvKey.STRUCTURED_SCHEMA_MAX_ENUM_VALUES, 500));
        }
    }

    private final ObjectMapper objectMapper;
    private final Limits limits;

    public StructuredOutputSchemaValidator(ObjectMapper objectMapper) {
        this(objectMapper, Limits.fromEnvironment());
    }

    StructuredOutputSchemaValidator(ObjectMapper objectMapper, Limits limits) {
        this.objectMapper = objectMapper;
        this.limits = limits;
    }

    public FinalOutputContract.JsonSchema validate(
            String name, JsonNode schema, boolean strict) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            fail("Schema name must match " + NAME_PATTERN.pattern(), "$",
                    Map.of("name", String.valueOf(name)));
        }
        if (schema == null || !schema.isObject()) {
            fail("Schema must be a JSON object", "$", Map.of());
        }
        if (!"object".equals(schema.path("type").asText())) {
            fail("Schema root type must be object", "$/type", Map.of());
        }
        int bytes = serializedSize(schema);
        if (bytes > limits.maxBytes()) {
            fail("Schema exceeds the configured byte limit", "$",
                    Map.of("actualBytes", bytes, "maxBytes", limits.maxBytes()));
        }

        Counter counter = new Counter();
        validateNode(schema, "$", 1, strict, counter);
        return new FinalOutputContract.JsonSchema(name, schema, strict);
    }

    private void validateNode(
            JsonNode schema,
            String path,
            int depth,
            boolean strict,
            Counter counter
    ) {
        if (!schema.isObject()) {
            fail("Each schema node must be an object", path, Map.of());
        }
        if (depth > limits.maxDepth()) {
            fail("Schema exceeds the configured depth limit", path,
                    Map.of("maxDepth", limits.maxDepth()));
        }
        if (schema.has("$ref")) {
            fail("Schema references are not supported; remote $ref is forbidden", path, Map.of());
        }
        Iterator<String> fieldNames = schema.fieldNames();
        while (fieldNames.hasNext()) {
            String keyword = fieldNames.next();
            if (!SUPPORTED_KEYWORDS.contains(keyword)) {
                fail("Unsupported JSON Schema keyword: " + keyword,
                        path + "/" + keyword, Map.of());
            }
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode == null || !typeNode.isTextual()
                || !SUPPORTED_TYPES.contains(typeNode.asText())) {
            fail("Schema type must be one of " + SUPPORTED_TYPES, path + "/type", Map.of());
        }

        JsonNode description = schema.get("description");
        if (description != null && !description.isTextual()) {
            fail("description must be a string", path + "/description", Map.of());
        }

        JsonNode enumNode = schema.get("enum");
        if (enumNode != null) {
            validateEnum(enumNode, path, counter);
        }

        switch (typeNode.asText()) {
            case "object" -> validateObject(schema, path, depth, strict, counter);
            case "array" -> validateArray(schema, path, depth, strict, counter);
            default -> rejectStructuralKeywords(schema, path);
        }
    }

    private void validateObject(
            JsonNode schema,
            String path,
            int depth,
            boolean strict,
            Counter counter
    ) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            fail("Object schema must define properties", path + "/properties", Map.of());
        }
        JsonNode required = schema.get("required");
        if (required == null || !required.isArray()) {
            fail("Object schema must define required as an array", path + "/required", Map.of());
        }
        Set<String> requiredNames = new HashSet<>();
        required.forEach(value -> {
            if (!value.isTextual()) {
                fail("required entries must be strings", path + "/required", Map.of());
            }
            requiredNames.add(value.asText());
        });

        Iterator<Map.Entry<String, JsonNode>> entries = properties.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            counter.properties++;
            if (counter.properties > limits.maxProperties()) {
                fail("Schema exceeds the configured property limit", path + "/properties",
                        Map.of("maxProperties", limits.maxProperties()));
            }
            validateNode(entry.getValue(), path + "/properties/" + entry.getKey(),
                    depth + 1, strict, counter);
        }
        for (String requiredName : requiredNames) {
            if (!properties.has(requiredName)) {
                fail("required references an unknown property", path + "/required",
                        Map.of("property", requiredName));
            }
        }

        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && !additionalProperties.isBoolean()) {
            fail("additionalProperties must be boolean",
                    path + "/additionalProperties", Map.of());
        }
        if (strict) {
            if (additionalProperties == null || additionalProperties.asBoolean()) {
                fail("Strict object schemas require additionalProperties=false",
                        path + "/additionalProperties", Map.of());
            }
            if (requiredNames.size() != properties.size()) {
                fail("Strict object schemas require every property",
                        path + "/required", Map.of());
            }
        }
        if (schema.has("items")) {
            fail("Object schema cannot define items", path + "/items", Map.of());
        }
    }

    private void validateArray(
            JsonNode schema,
            String path,
            int depth,
            boolean strict,
            Counter counter
    ) {
        JsonNode items = schema.get("items");
        if (items == null) {
            fail("Array schema must define items", path + "/items", Map.of());
        }
        validateNode(items, path + "/items", depth + 1, strict, counter);
        if (schema.has("properties") || schema.has("required")
                || schema.has("additionalProperties")) {
            fail("Array schema cannot define object keywords", path, Map.of());
        }
    }

    private void validateEnum(JsonNode enumNode, String path, Counter counter) {
        if (!enumNode.isArray() || enumNode.isEmpty()) {
            fail("enum must be a non-empty array", path + "/enum", Map.of());
        }
        for (JsonNode value : enumNode) {
            if (!value.isTextual()) {
                fail("Only string enum values are supported", path + "/enum", Map.of());
            }
            counter.enumValues++;
            if (counter.enumValues > limits.maxEnumValues()) {
                fail("Schema exceeds the configured enum value limit", path + "/enum",
                        Map.of("maxEnumValues", limits.maxEnumValues()));
            }
        }
    }

    private void rejectStructuralKeywords(JsonNode schema, String path) {
        if (schema.has("properties") || schema.has("required")
                || schema.has("additionalProperties") || schema.has("items")) {
            fail("Scalar schema cannot define object or array keywords", path, Map.of());
        }
    }

    private int serializedSize(JsonNode schema) {
        try {
            return objectMapper.writeValueAsBytes(schema).length;
        } catch (JsonProcessingException e) {
            throw new StructuredOutputException(
                    StructuredOutputException.Code.STRUCTURED_OUTPUT_SCHEMA_INVALID,
                    "Schema cannot be serialized", Map.of(), e);
        }
    }

    private static void fail(String message, String path, Map<String, Object> details) {
        java.util.HashMap<String, Object> errorDetails = new java.util.HashMap<>(details);
        errorDetails.put("path", path);
        throw new StructuredOutputException(
                StructuredOutputException.Code.STRUCTURED_OUTPUT_SCHEMA_INVALID,
                message, errorDetails);
    }

    private static final class Counter {
        private int properties;
        private int enumValues;
    }
}
