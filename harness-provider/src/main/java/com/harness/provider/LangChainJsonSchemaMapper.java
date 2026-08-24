package com.harness.provider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.List;

/** Converts provider-neutral JSON Schema documents into LangChain4j schema elements. */
public final class LangChainJsonSchemaMapper {

    private LangChainJsonSchemaMapper() {}

    public static JsonObjectSchema toObjectSchema(JsonNode schemaNode) {
        JsonSchemaElement schema = toSchemaElement(schemaNode);
        if (schema instanceof JsonObjectSchema objectSchema) {
            return objectSchema;
        }
        throw new IllegalArgumentException("JSON Schema root must be an object");
    }

    public static JsonSchemaElement toSchemaElement(JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isNull()) {
            throw new IllegalArgumentException("JSON Schema must not be null");
        }

        String description = textValue(schemaNode, "description");
        if (schemaNode.has("enum") && schemaNode.get("enum").isArray()) {
            List<String> values = new ArrayList<>();
            schemaNode.get("enum").forEach(value -> values.add(value.asText()));
            return JsonEnumSchema.builder()
                    .description(description)
                    .enumValues(values)
                    .build();
        }

        String type = inferType(schemaNode);
        return switch (type) {
            case "object" -> buildObjectSchema(schemaNode, description);
            case "array" -> buildArraySchema(schemaNode, description);
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "string" -> JsonStringSchema.builder().description(description).build();
            default -> throw new IllegalArgumentException(
                    "Unsupported JSON Schema type: " + type);
        };
    }

    private static JsonObjectSchema buildObjectSchema(JsonNode schemaNode, String description) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder().description(description);
        JsonNode properties = schemaNode.get("properties");
        if (properties != null && properties.isObject()) {
            properties.properties().forEach(entry ->
                    builder.addProperty(entry.getKey(), toSchemaElement(entry.getValue())));
        }
        JsonNode required = schemaNode.get("required");
        if (required != null && required.isArray()) {
            List<String> requiredNames = new ArrayList<>();
            required.forEach(value -> requiredNames.add(value.asText()));
            builder.required(requiredNames);
        }
        JsonNode additionalProperties = schemaNode.get("additionalProperties");
        if (additionalProperties != null && additionalProperties.isBoolean()) {
            builder.additionalProperties(additionalProperties.asBoolean());
        }
        return builder.build();
    }

    private static JsonArraySchema buildArraySchema(JsonNode schemaNode, String description) {
        JsonNode items = schemaNode.get("items");
        if (items == null || items.isNull()) {
            throw new IllegalArgumentException("Array JSON Schema must define items");
        }
        return JsonArraySchema.builder()
                .description(description)
                .items(toSchemaElement(items))
                .build();
    }

    private static String inferType(JsonNode schemaNode) {
        JsonNode typeNode = schemaNode.get("type");
        if (typeNode != null && typeNode.isTextual()) return typeNode.asText();
        if (schemaNode.has("properties")) return "object";
        if (schemaNode.has("items")) return "array";
        throw new IllegalArgumentException("JSON Schema node must declare a type");
    }

    private static String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
