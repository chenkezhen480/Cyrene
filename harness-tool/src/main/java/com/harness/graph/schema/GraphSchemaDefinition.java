package com.harness.graph.schema;

import java.util.Map;

public record GraphSchemaDefinition(
        String schemaId,
        int version,
        GraphSchemaMode mode,
        Map<String, GraphNodeTypeDefinition> nodeTypes,
        Map<String, GraphRelationTypeDefinition> relationTypes,
        int defaultMaxDepth,
        int maxDepth
) {
    public GraphSchemaDefinition {
        GraphSchemaSupport.requireSchemaId(schemaId);
        if (version <= 0) {
            throw new IllegalArgumentException("schema version must be greater than 0");
        }
        if (mode == null) {
            throw new IllegalArgumentException("schema mode is required");
        }
        nodeTypes = nodeTypes == null ? Map.of() : Map.copyOf(nodeTypes);
        relationTypes = relationTypes == null ? Map.of() : Map.copyOf(relationTypes);
        Map<String, GraphNodeTypeDefinition> validatedNodeTypes = nodeTypes;
        if (nodeTypes.isEmpty()) {
            throw new IllegalArgumentException("schema must define at least one node type");
        }
        nodeTypes.forEach((label, definition) -> {
            if (!label.equals(definition.label())) {
                throw new IllegalArgumentException("node type key does not match label: " + label);
            }
        });
        relationTypes.forEach((type, definition) -> {
            if (!type.equals(definition.relationType())) {
                throw new IllegalArgumentException("relation type key does not match definition: " + type);
            }
            definition.sourceLabels().forEach(sourceLabel -> requireKnownLabel(validatedNodeTypes, sourceLabel));
            definition.targetLabels().forEach(targetLabel -> requireKnownLabel(validatedNodeTypes, targetLabel));
        });
        if (defaultMaxDepth <= 0 || maxDepth <= 0 || defaultMaxDepth > maxDepth) {
            throw new IllegalArgumentException("depth values must be positive and defaultMaxDepth cannot exceed maxDepth");
        }
    }

    public GraphNodeTypeDefinition requireNodeType(String label) {
        GraphNodeTypeDefinition definition = nodeTypes.get(label);
        if (definition == null) {
            throw new GraphSchemaValidationException("Node label is not allowed by schema '" + schemaId + "': " + label);
        }
        return definition;
    }

    public GraphRelationTypeDefinition requireRelationType(String relationType) {
        GraphRelationTypeDefinition definition = relationTypes.get(relationType);
        if (definition == null) {
            throw new GraphSchemaValidationException(
                    "Relation type is not allowed by schema '" + schemaId + "': " + relationType);
        }
        return definition;
    }

    private static void requireKnownLabel(Map<String, GraphNodeTypeDefinition> nodeTypes, String label) {
        if (!nodeTypes.containsKey(label)) {
            throw new IllegalArgumentException("relation references unknown node label: " + label);
        }
    }
}
