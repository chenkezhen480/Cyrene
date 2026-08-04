package com.harness.graph.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Collections;

/**
 * Deterministic, non-LLM formatter that removes schema-marked sensitive properties.
 */
public final class DefaultGraphResultFormatter implements GraphResultFormatter {

    private final GraphSchemaDefinition schema;
    private final GraphSettings settings;
    private final ObjectMapper objectMapper;

    public DefaultGraphResultFormatter(
            GraphSchemaDefinition schema,
            GraphSettings settings,
            ObjectMapper objectMapper
    ) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String schemaId() {
        return schema.schemaId();
    }

    @Override
    public String format(GraphRouteResult result) {
        if (result == null || result.isEmpty()) {
            return "";
        }
        StringBuilder output = new StringBuilder("[Structured Knowledge Graph]\n");
        int itemCount = 0;
        for (GraphNode node : result.nodes()) {
            if (itemCount >= settings.contextMaxItems()) break;
            appendWithinLimit(output, "- node ", node.nodeId(), " labels=", node.labels().toString(),
                    " properties=", json(visibleNodeProperties(node)));
            itemCount++;
        }
        for (GraphRelation relation : result.relations()) {
            if (itemCount >= settings.contextMaxItems()) break;
            appendWithinLimit(output, "- relation ", relation.relationId(), ": ",
                    relation.sourceNodeId(), " -[", relation.relationType(), "]-> ",
                    relation.targetNodeId(), " properties=", json(visibleRelationProperties(relation)));
            itemCount++;
        }
        if (result.paths().size() > 0 && itemCount < settings.contextMaxItems()) {
            appendWithinLimit(output, "- pathCount=", Integer.toString(result.paths().size()));
        }
        return output.length() > settings.contextMaxChars()
                ? output.substring(0, settings.contextMaxChars())
                : output.toString();
    }

    private Map<String, Object> visibleNodeProperties(GraphNode node) {
        Map<String, GraphPropertyDefinition> definitions = new LinkedHashMap<>();
        for (String label : node.labels()) {
            GraphNodeTypeDefinition nodeType = schema.requireNodeType(label);
            nodeType.properties().forEach(definitions::putIfAbsent);
        }
        return visibleProperties(node.properties(), definitions);
    }

    private Map<String, Object> visibleRelationProperties(GraphRelation relation) {
        GraphRelationTypeDefinition relationType = schema.requireRelationType(relation.relationType());
        return visibleProperties(relation.properties(), relationType.properties());
    }

    private static Map<String, Object> visibleProperties(
            Map<String, Object> properties,
            Map<String, GraphPropertyDefinition> definitions
    ) {
        Map<String, Object> visible = new TreeMap<>();
        properties.forEach((name, value) -> {
            GraphPropertyDefinition definition = definitions.get(name);
            if (definition != null && !definition.sensitive()) {
                visible.put(name, value);
            }
        });
        return Collections.unmodifiableMap(visible);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to format graph properties", e);
        }
    }

    private void appendWithinLimit(StringBuilder output, String... parts) {
        if (output.length() >= settings.contextMaxChars()) {
            return;
        }
        for (String part : parts) {
            if (output.length() + part.length() > settings.contextMaxChars()) {
                output.append(part, 0, settings.contextMaxChars() - output.length());
                return;
            }
            output.append(part);
        }
        if (output.length() < settings.contextMaxChars()) {
            output.append('\n');
        }
    }
}
