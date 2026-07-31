package com.harness.graph.schema;

import com.harness.graph.model.GraphMutationBatch;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class GraphSchemaValidator {

    private final GraphSchemaRegistry schemaRegistry;

    public GraphSchemaValidator(GraphSchemaRegistry schemaRegistry) {
        if (schemaRegistry == null) {
            throw new IllegalArgumentException("schemaRegistry is required");
        }
        this.schemaRegistry = schemaRegistry;
    }

    public void validate(GraphMutationBatch mutationBatch) {
        if (mutationBatch == null) {
            throw new IllegalArgumentException("mutationBatch is required");
        }
        GraphSchemaDefinition schema = schemaRegistry.require(mutationBatch.schemaId());
        if (schema.mode() != GraphSchemaMode.STRICT) {
            throw new GraphSchemaValidationException(
                    "Only STRICT graph schemas are supported in the first version: " + schema.schemaId());
        }

        Map<String, Set<String>> batchNodeLabels = new HashMap<>();
        for (GraphNode node : mutationBatch.nodes()) {
            validateNode(schema, node);
            batchNodeLabels.put(node.nodeId(), node.labels());
        }
        for (GraphRelation relation : mutationBatch.relations()) {
            validateRelation(schema, relation, batchNodeLabels);
        }
    }

    public void validateNodeLabel(String schemaId, String label) {
        GraphSchemaDefinition schema = schemaRegistry.require(schemaId);
        if (label == null || label.isBlank()) {
            return;
        }
        schema.requireNodeType(label);
    }

    public void validateRelationType(String schemaId, String relationType) {
        GraphSchemaDefinition schema = schemaRegistry.require(schemaId);
        if (relationType == null || relationType.isBlank()) {
            return;
        }
        schema.requireRelationType(relationType);
    }

    private static void validateNode(GraphSchemaDefinition schema, GraphNode node) {
        Map<String, GraphPropertyDefinition> mergedProperties = new HashMap<>();
        for (String label : node.labels()) {
            GraphNodeTypeDefinition type = schema.requireNodeType(label);
            type.properties().forEach((name, definition) -> {
                GraphPropertyDefinition previous = mergedProperties.putIfAbsent(name, definition);
                if (previous != null && previous.type() != definition.type()) {
                    throw new GraphSchemaValidationException(
                            "Node labels declare conflicting types for property: " + name);
                }
            });
        }
        validateProperties("node " + node.nodeId(), node.properties(), mergedProperties);
    }

    private static void validateRelation(
            GraphSchemaDefinition schema,
            GraphRelation relation,
            Map<String, Set<String>> batchNodeLabels
    ) {
        GraphRelationTypeDefinition type = schema.requireRelationType(relation.relationType());
        validateProperties("relation " + relation.relationId(), relation.properties(), type.properties());

        Set<String> sourceLabels = batchNodeLabels.get(relation.sourceNodeId());
        if (sourceLabels != null && sourceLabels.stream().noneMatch(type.sourceLabels()::contains)) {
            throw new GraphSchemaValidationException(
                    "Relation " + relation.relationId() + " source node type is not allowed");
        }
        Set<String> targetLabels = batchNodeLabels.get(relation.targetNodeId());
        if (targetLabels != null && targetLabels.stream().noneMatch(type.targetLabels()::contains)) {
            throw new GraphSchemaValidationException(
                    "Relation " + relation.relationId() + " target node type is not allowed");
        }
    }

    private static void validateProperties(
            String owner,
            Map<String, Object> values,
            Map<String, GraphPropertyDefinition> definitions
    ) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            GraphPropertyDefinition definition = definitions.get(entry.getKey());
            if (definition == null) {
                throw new GraphSchemaValidationException(
                        owner + " contains property not declared by schema: " + entry.getKey());
            }
            if (!definition.type().accepts(entry.getValue())) {
                throw new GraphSchemaValidationException(
                        owner + " property '" + entry.getKey() + "' must be " + definition.type());
            }
        }
        definitions.values().stream()
                .filter(GraphPropertyDefinition::required)
                .filter(definition -> !values.containsKey(definition.name()))
                .findFirst()
                .ifPresent(definition -> {
                    throw new GraphSchemaValidationException(
                            owner + " is missing required property: " + definition.name());
                });
    }
}
