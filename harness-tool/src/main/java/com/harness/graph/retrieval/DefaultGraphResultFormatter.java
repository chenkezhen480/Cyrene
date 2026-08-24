package com.harness.graph.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNode;
import com.harness.graph.model.GraphPath;
import com.harness.graph.model.GraphRelation;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.schema.GraphNodeTypeDefinition;
import com.harness.graph.schema.GraphPropertyDefinition;
import com.harness.graph.schema.GraphRelationTypeDefinition;
import com.harness.graph.schema.GraphSchemaDefinition;
import com.harness.tool.protocol.ToolEnvelope;
import com.harness.tool.protocol.ToolEnvelopeStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds a bounded model-facing graph DTO while removing schema-marked sensitive properties.
 * Limits are applied before serialization, so output is always one complete JSON document.
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
    public ToolEnvelope<GraphToolData> format(String graphId, GraphRouteResult source) {
        if (graphId == null || graphId.isBlank()) {
            throw new IllegalArgumentException("graphId is required");
        }
        GraphRouteResult result = source != null ? source : GraphRouteResult.empty();
        ToolEnvelopeStatus status = result.isEmpty()
                ? ToolEnvelopeStatus.EMPTY
                : ToolEnvelopeStatus.SUCCESS;
        List<GraphToolData.Node> nodes = new ArrayList<>();
        List<GraphToolData.Relation> relations = new ArrayList<>();
        List<GraphToolData.Path> paths = new ArrayList<>();
        boolean truncated = !result.aggregates().isEmpty();
        int itemCount = 0;

        ensureBaseEnvelopeFits(graphId, status, result);
        for (GraphNode node : result.nodes()) {
            if (itemCount >= settings.contextMaxItems()) {
                truncated = true;
                break;
            }
            nodes.add(toNode(node));
            if (!fits(graphId, status, result, nodes, relations, paths, truncated)) {
                nodes.remove(nodes.size() - 1);
                truncated = true;
                break;
            }
            itemCount++;
        }
        if (nodes.size() < result.nodes().size()) {
            truncated = true;
        }

        for (GraphRelation relation : result.relations()) {
            if (itemCount >= settings.contextMaxItems()) {
                truncated = true;
                break;
            }
            relations.add(toRelation(relation));
            if (!fits(graphId, status, result, nodes, relations, paths, truncated)) {
                relations.remove(relations.size() - 1);
                truncated = true;
                break;
            }
            itemCount++;
        }
        if (relations.size() < result.relations().size()) {
            truncated = true;
        }

        for (GraphPath path : result.paths()) {
            int pathItems = 1 + path.nodes().size() + path.relations().size();
            if (itemCount + pathItems > settings.contextMaxItems()) {
                truncated = true;
                break;
            }
            paths.add(toPath(path));
            if (!fits(graphId, status, result, nodes, relations, paths, truncated)) {
                paths.remove(paths.size() - 1);
                truncated = true;
                break;
            }
            itemCount += pathItems;
        }
        if (paths.size() < result.paths().size()) {
            truncated = true;
        }

        return envelope(graphId, status, result, nodes, relations, paths, truncated);
    }

    private void ensureBaseEnvelopeFits(
            String graphId, ToolEnvelopeStatus status, GraphRouteResult result) {
        ToolEnvelope<GraphToolData> base = envelope(
                graphId, status, result, List.of(), List.of(), List.of(), false);
        if (serializedLength(base) > settings.contextMaxChars()) {
            throw new IllegalStateException(
                    "Graph envelope identifiers exceed HARNESS_GRAPH_CONTEXT_MAX_CHARS");
        }
    }

    private boolean fits(
            String graphId,
            ToolEnvelopeStatus status,
            GraphRouteResult result,
            List<GraphToolData.Node> nodes,
            List<GraphToolData.Relation> relations,
            List<GraphToolData.Path> paths,
            boolean truncated
    ) {
        return serializedLength(envelope(
                graphId, status, result, nodes, relations, paths, truncated))
                <= settings.contextMaxChars();
    }

    private ToolEnvelope<GraphToolData> envelope(
            String graphId,
            ToolEnvelopeStatus status,
            GraphRouteResult result,
            List<GraphToolData.Node> nodes,
            List<GraphToolData.Relation> relations,
            List<GraphToolData.Path> paths,
            boolean truncated
    ) {
        GraphToolData data = new GraphToolData(
                graphId, schema.schemaId(), nodes, relations, paths);
        return new ToolEnvelope<>(
                status,
                data,
                result.pageInfo(),
                Map.of("truncated", truncated));
    }

    private GraphToolData.Node toNode(GraphNode node) {
        Map<String, GraphPropertyDefinition> definitions = new LinkedHashMap<>();
        List<String> labels = node.labels().stream().sorted().toList();
        for (String label : labels) {
            GraphNodeTypeDefinition nodeType = schema.requireNodeType(label);
            nodeType.properties().forEach(definitions::putIfAbsent);
        }
        return new GraphToolData.Node(
                node.nodeId(), labels, visibleProperties(node.properties(), definitions));
    }

    private GraphToolData.Relation toRelation(GraphRelation relation) {
        GraphRelationTypeDefinition relationType =
                schema.requireRelationType(relation.relationType());
        return new GraphToolData.Relation(
                relation.relationId(),
                relation.sourceNodeId(),
                relation.targetNodeId(),
                relation.relationType(),
                visibleProperties(relation.properties(), relationType.properties()));
    }

    private GraphToolData.Path toPath(GraphPath path) {
        return new GraphToolData.Path(
                path.nodes().stream().map(this::toNode).toList(),
                path.relations().stream().map(this::toRelation).toList(),
                path.depth());
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

    private int serializedLength(Object value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize graph result DTO", e);
        }
    }
}
