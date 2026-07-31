package com.harness.graph.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CanonicalJsonGraphDataConverter implements GraphDataConverter {

    public static final String CONVERTER_ID = "canonical-json";
    private static final Set<String> ALLOWED_FIELDS = Set.of("nodes", "relations");

    private final ObjectMapper objectMapper;

    public CanonicalJsonGraphDataConverter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String converterId() {
        return CONVERTER_ID;
    }

    @Override
    public GraphBuildSourceType sourceType() {
        return GraphBuildSourceType.STRUCTURED;
    }

    @Override
    public GraphMutationDraft convert(GraphBuildRequest request) {
        if (request.sourceType() != sourceType()) {
            throw new IllegalArgumentException("canonical-json only accepts structured sources");
        }
        JsonNode source = request.source();
        if (!source.isObject()) {
            throw new IllegalArgumentException(
                    "canonical-json source must be an object containing nodes and/or relations");
        }

        Set<String> unknownFields = new HashSet<>();
        source.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                unknownFields.add(field);
            }
        });
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "canonical-json source contains unsupported fields: " + unknownFields);
        }

        return new GraphMutationDraft(
                readList(source, "nodes", GraphNode.class),
                readList(source, "relations", GraphRelation.class)
        );
    }

    private <T> List<T> readList(JsonNode source, String fieldName, Class<T> elementType) {
        JsonNode value = source.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be a JSON array");
        }
        try {
            return objectMapper.readerForListOf(elementType).readValue(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to parse canonical graph " + fieldName + ": " + e.getMessage(), e);
        }
    }
}
