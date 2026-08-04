package com.harness.graph.neo4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Neo4jValueMapper {

    private static final Set<String> RESERVED_NODE_PROPERTIES = Set.of(
            "storageKey", "nodeId", "graphId", "schemaId", "createdAt", "updatedAt");
    private static final Set<String> RESERVED_RELATION_PROPERTIES = Set.of(
            "storageKey", "relationId", "graphId", "schemaId", "createdAt", "updatedAt");

    private final ObjectMapper objectMapper;

    Neo4jValueMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, Object> toStorageProperties(Map<String, Object> properties) {
        Map<String, Object> result = new LinkedHashMap<>();
        properties.forEach((key, value) -> result.put(key, toStorageValue(value)));
        return result;
    }

    Map<String, Object> nodeProperties(Node node) {
        return withoutReserved(node.asMap(Value::asObject), RESERVED_NODE_PROPERTIES);
    }

    Map<String, Object> relationProperties(Relationship relationship) {
        return withoutReserved(relationship.asMap(Value::asObject), RESERVED_RELATION_PROPERTIES);
    }

    private Object toStorageValue(Object value) {
        if (value instanceof Instant instant) {
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (value instanceof TemporalAccessor
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.stream().allMatch(this::isNeo4jScalar)) {
                return List.copyOf(collection);
            }
            return toJson(value);
        }
        if (value instanceof Map<?, ?>) {
            return toJson(value);
        }
        throw new IllegalArgumentException("Unsupported Neo4j property value type: " + value.getClass().getName());
    }

    private boolean isNeo4jScalar(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Number
                || value instanceof TemporalAccessor;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Graph property cannot be serialized as JSON", e);
        }
    }

    private static Map<String, Object> withoutReserved(Map<String, Object> source, Set<String> reserved) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        reserved.forEach(result::remove);
        return Map.copyOf(result);
    }
}
