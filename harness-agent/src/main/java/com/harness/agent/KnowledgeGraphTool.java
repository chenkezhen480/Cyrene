package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.retrieval.AnchoredNeighborhoodGraphRetriever;
import com.harness.graph.retrieval.DefaultGraphResultFormatter;
import com.harness.graph.retrieval.GraphKnowledgeRetriever;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.tool.Tool;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Controlled graph retrieval tool. Graph, schema and subject scope never come from LLM arguments.
 */
public final class KnowledgeGraphTool implements Tool {

    public static final String TOOL_NAME = "knowledge_graph_search";
    private static final ThreadLocal<GraphRequestContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final GraphKnowledgeRetriever retriever;
    private final GraphSchemaRegistry schemaRegistry;
    private final GraphSettings settings;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphTool(
            GraphKnowledgeRetriever retriever,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings,
            ObjectMapper objectMapper
    ) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public static void setCurrentContext(GraphRequestContext requestContext) {
        if (requestContext == null) {
            CURRENT_CONTEXT.remove();
        } else {
            CURRENT_CONTEXT.set(requestContext);
        }
    }

    public static void clearCurrentContext() {
        CURRENT_CONTEXT.remove();
    }

    @Override
    public ToolSpec spec() {
        var properties = objectMapper.createObjectNode();
        properties.set("queryId", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Registered graph query ID. Use anchored-neighborhood unless instructed otherwise."));
        properties.set("relationTypes", objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "string")));
        properties.set("maxDepth", objectMapper.createObjectNode()
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "Requested traversal depth; server and schema limits always apply."));
        properties.set("limit", objectMapper.createObjectNode()
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "Requested result limit; server maximum always applies."));
        return new ToolSpec(
                TOOL_NAME,
                "Retrieve structured nodes, relations, and paths for the server-scoped subjects. "
                        + "This route is independent from vector document search and does not use reranking. "
                        + "Do not infer or request graph IDs, schema IDs, subject IDs, or Cypher.",
                objectMapper.createObjectNode()
                        .put("type", "object")
                        .set("properties", properties)
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        GraphRequestContext requestContext = CURRENT_CONTEXT.get();
        if (requestContext == null) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "No server-authorized graph request context is available"
            );
        }
        try {
            String queryId = text(arguments, "queryId", AnchoredNeighborhoodGraphRetriever.QUERY_ID);
            int maxDepth = integer(arguments, "maxDepth");
            int limit = integer(arguments, "limit");
            Set<String> relationTypes = stringSet(arguments, "relationTypes");
            GraphRouteResult result = retriever.retrieve(
                    requestContext,
                    queryId,
                    relationTypes,
                    maxDepth,
                    limit
            );
            if (result.isEmpty()) {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
                return "No structured graph records were found for the authorized subjects.";
            }
            String formatted = new DefaultGraphResultFormatter(
                    schemaRegistry.require(requestContext.schemaId()),
                    settings,
                    objectMapper
            ).format(result);
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            return formatted;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "Knowledge graph search failed: " + e.getMessage()
            );
        }
    }

    private static String text(JsonNode arguments, String name, String defaultValue) {
        if (arguments == null || !arguments.hasNonNull(name) || arguments.get(name).asText().isBlank()) {
            return defaultValue;
        }
        return arguments.get(name).asText();
    }

    private static int integer(JsonNode arguments, String name) {
        return arguments != null && arguments.has(name) ? arguments.get(name).asInt(0) : 0;
    }

    private static Set<String> stringSet(JsonNode arguments, String name) {
        if (arguments == null || !arguments.has(name) || !arguments.get(name).isArray()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        arguments.get(name).forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        });
        return Set.copyOf(values);
    }
}
