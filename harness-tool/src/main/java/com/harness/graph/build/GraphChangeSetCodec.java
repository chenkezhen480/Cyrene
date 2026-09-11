package com.harness.graph.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic JSON used for durable graph mutation replay and requestId collision checks. */
public final class GraphChangeSetCodec {

    private final ObjectMapper objectMapper;

    public GraphChangeSetCodec() {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public Encoded encode(GraphChangeSet changeSet) {
        if (changeSet == null) throw new IllegalArgumentException("changeSet is required");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("requestId", changeSet.requestId());
        root.put("graphId", changeSet.graphId());
        root.put("schemaId", changeSet.schemaId());
        ArrayNode nodes = root.putArray("nodes");
        changeSet.nodes().stream()
                .sorted(Comparator.comparing(GraphNode::nodeId))
                .forEach(node -> {
                    ObjectNode value = nodes.addObject();
                    value.put("nodeId", node.nodeId());
                    ArrayNode labels = value.putArray("labels");
                    new TreeSet<>(node.labels()).forEach(labels::add);
                    value.set("properties", sortedProperties(node.properties()));
                });
        ArrayNode relations = root.putArray("relations");
        changeSet.relations().stream()
                .sorted(Comparator.comparing(GraphRelation::relationId))
                .forEach(relation -> {
                    ObjectNode value = relations.addObject();
                    value.put("relationId", relation.relationId());
                    value.put("sourceNodeId", relation.sourceNodeId());
                    value.put("targetNodeId", relation.targetNodeId());
                    value.put("relationType", relation.relationType());
                    value.set("properties", sortedProperties(relation.properties()));
                });
        ArrayNode deleteNodeIds = root.putArray("deleteNodeIds");
        new TreeSet<>(changeSet.deleteNodeIds()).forEach(deleteNodeIds::add);
        ArrayNode deleteRelationIds = root.putArray("deleteRelationIds");
        new TreeSet<>(changeSet.deleteRelationIds()).forEach(deleteRelationIds::add);
        String json = write(root);
        return new Encoded(json, KnowledgeIdentity.sha256(json));
    }

    public GraphChangeSet decode(String canonicalPayload) {
        if (canonicalPayload == null || canonicalPayload.isBlank()) {
            throw new IllegalArgumentException("canonicalPayload is required");
        }
        try {
            return objectMapper.readValue(canonicalPayload, GraphChangeSet.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid canonical GraphChangeSet", exception);
        }
    }

    private JsonNode sortedProperties(Map<String, Object> properties) {
        return objectMapper.valueToTree(new TreeMap<>(properties));
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Failed to encode GraphChangeSet", exception);
        }
    }

    public record Encoded(String canonicalPayload, String payloadHash) {
    }
}
