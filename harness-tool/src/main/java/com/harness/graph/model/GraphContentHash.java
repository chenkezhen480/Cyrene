package com.harness.graph.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.TreeMap;

public final class GraphContentHash {
    private GraphContentHash() { }

    public static String of(Object value, ObjectMapper mapper) {
        return bytes(canonical(value, mapper).toString().getBytes(StandardCharsets.UTF_8));
    }

    public static JsonNode canonical(Object value, ObjectMapper mapper) {
        return normalize(mapper.valueToTree(value), mapper, "", false);
    }

    public static String bytes(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static JsonNode normalize(JsonNode node, ObjectMapper mapper, String field, boolean propertyValue) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, value) -> result.set(key, normalize(value, mapper,
                    field.equals("incidentRelationIds") ? "deleteRelationIds" : key,
                    propertyValue || key.equals("properties") && (node.has("nodeId") || node.has("relationId")))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            ArrayList<JsonNode> sorted = new ArrayList<>();
            node.forEach(value -> sorted.add(normalize(value, mapper, "", propertyValue)));
            if (!propertyValue && java.util.Set.of("labels", "sourceLabels", "targetLabels", "deleteNodeIds", "deleteRelationIds").contains(field))
                sorted.sort(java.util.Comparator.comparing(JsonNode::toString));
            sorted.forEach(result::add);
            return result;
        }
        return node;
    }
}
