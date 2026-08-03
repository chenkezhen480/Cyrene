package com.harness.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.agent.graph.GraphSpaceReference;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.retrieval.AnchoredNeighborhoodGraphRetriever;
import com.harness.graph.retrieval.DefaultGraphResultFormatter;
import com.harness.graph.retrieval.GraphKnowledgeRetriever;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Structured graph discovery and retrieval tool.
 *
 * <p>A server-provided request scope always takes precedence. Without one, the tool can discover
 * graph spaces and nodes autonomously, subject to the configured graph-space access service.</p>
 */
public final class KnowledgeGraphTool implements Tool {

    public static final String TOOL_NAME = "knowledge_graph_search";
    static final String ACTION_LIST_GRAPH_SPACES = "listGraphSpaces";
    static final String ACTION_FIND_NODES = "findNodes";
    static final String ACTION_FIND_NEIGHBORHOOD = "findNeighborhood";

    private static final ThreadLocal<RuntimeContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final GraphKnowledgeRetriever retriever;
    private final KnowledgeGraphStore graphStore;
    private final GraphSpaceAccessService graphSpaceAccessService;
    private final GraphSchemaRegistry schemaRegistry;
    private final GraphSettings settings;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphTool(
            GraphKnowledgeRetriever retriever,
            KnowledgeGraphStore graphStore,
            GraphSpaceAccessService graphSpaceAccessService,
            GraphSchemaRegistry schemaRegistry,
            GraphSettings settings,
            ObjectMapper objectMapper
    ) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.graphSpaceAccessService = Objects.requireNonNull(
                graphSpaceAccessService,
                "graphSpaceAccessService"
        );
        this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public static void setCurrentContext(String tenantId, GraphRequestContext requestContext) {
        CURRENT_CONTEXT.set(new RuntimeContext(tenantId, requestContext));
    }

    static ContextSnapshot captureCurrentContext() {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        return runtimeContext == null
                ? null
                : new ContextSnapshot(runtimeContext.tenantId(), runtimeContext.requestContext());
    }

    static void restoreCurrentContext(ContextSnapshot contextSnapshot) {
        if (contextSnapshot == null) {
            CURRENT_CONTEXT.remove();
            return;
        }
        setCurrentContext(contextSnapshot.tenantId(), contextSnapshot.requestContext());
    }

    public static void clearCurrentContext() {
        CURRENT_CONTEXT.remove();
    }

    @Override
    public ToolSpec spec() {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        GraphRequestContext serverContext = runtimeContext == null
                ? null
                : runtimeContext.requestContext();
        boolean graphScoped = serverContext != null;
        boolean subjectScoped = graphScoped && serverContext.hasSubjectScope();
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode action = stringProperty(
                subjectScoped
                        ? "The server supplied the graph space and subject nodes. Use findNeighborhood."
                        : graphScoped
                                ? "The server supplied the graph space. Use findNodes once, then findNeighborhood once."
                                : "Use the shortest sequence: listGraphSpaces once, findNodes once, then "
                                        + "findNeighborhood once."
        );
        var actionValues = action.putArray("enum");
        if (subjectScoped) {
            actionValues.add(ACTION_FIND_NEIGHBORHOOD);
        } else if (graphScoped) {
            actionValues.add(ACTION_FIND_NODES)
                    .add(ACTION_FIND_NEIGHBORHOOD);
        } else {
            actionValues.add(ACTION_LIST_GRAPH_SPACES)
                    .add(ACTION_FIND_NODES)
                    .add(ACTION_FIND_NEIGHBORHOOD);
        }
        properties.set("action", action);
        if (!graphScoped) {
            properties.set("graphId", stringProperty(
                    "Graph-space ID returned by listGraphSpaces. Never invent this value."
            ));
            properties.set("schemaId", stringProperty(
                    "Schema ID returned by listGraphSpaces. Never invent this value."
            ));
        }
        if (!subjectScoped) {
            properties.set("name", stringProperty(
                    "Optional case-insensitive node name filter for findNodes."
            ));
            properties.set("label", stringProperty(
                    "Optional Schema node label filter for findNodes."
            ));
            properties.set("subjectIds", arrayProperty(
                    "Node IDs returned by findNodes and used as neighborhood anchors."
            ));
            properties.set("cursor", stringProperty(
                    "Opaque nextCursor returned by listGraphSpaces or findNodes."
            ));
        }
        properties.set("relationTypes", arrayProperty(
                "Optional Schema relation types to include in a neighborhood."
        ));
        properties.set("queryId", stringProperty(
                "Registered graph query ID. Use anchored-neighborhood unless instructed otherwise."
        ));
        properties.set("maxDepth", objectMapper.createObjectNode()
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "Traversal depth; server and Schema limits always apply."));
        properties.set("limit", objectMapper.createObjectNode()
                .put("type", "integer")
                .put("minimum", 1)
                .put("description", "Page or result size; the server maximum always applies."));

        return new ToolSpec(
                TOOL_NAME,
                subjectScoped
                        ? "Retrieve the server-authorized structured graph neighborhood in one call. "
                                + "The graph space and subject nodes are already fixed by the server. "
                                + "Do not discover spaces or nodes, generate Cypher, or guess identifiers."
                        : graphScoped
                                ? "Find the named entity inside the server-authorized graph space once, then retrieve "
                                        + "its structured neighborhood once. The graphId and schemaId are fixed by the "
                                        + "server. Do not list graph spaces, generate Cypher, or guess identifiers."
                                : "Discover and retrieve structured graph spaces, nodes, relations, and paths. "
                                + "This route is independent from vector document search and does not use reranking. "
                                + "For a named entity relationship question, call listGraphSpaces once, choose the "
                                + "best space from its description, call findNodes once with the entity name, then "
                                + "call findNeighborhood once with every matching node ID. Do not repeat an identical "
                                + "failed call, generate Cypher, guess identifiers, or search unrelated graph spaces.",
                objectMapper.createObjectNode()
                        .put("type", "object")
                        .set("properties", properties)
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        if (runtimeContext == null) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "No graph tool runtime context is available"
            );
        }

        try {
            GraphRequestContext serverContext = runtimeContext.requestContext();
            String defaultAction = serverContext == null
                    ? ACTION_LIST_GRAPH_SPACES
                    : serverContext.hasSubjectScope()
                            ? ACTION_FIND_NEIGHBORHOOD
                            : ACTION_FIND_NODES;
            String action = text(arguments, "action", defaultAction);
            requireAllowedAction(serverContext, action);
            runtimeContext.requireFreshInvocation(action, canonicalArguments(arguments));
            return switch (action) {
                case ACTION_LIST_GRAPH_SPACES -> listGraphSpaces(arguments, runtimeContext);
                case ACTION_FIND_NODES -> findNodes(arguments, runtimeContext);
                case ACTION_FIND_NEIGHBORHOOD -> findNeighborhood(arguments, runtimeContext);
                default -> throw new IllegalArgumentException("Unsupported graph action: " + action);
            };
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException(
                    TOOL_NAME,
                    "Knowledge graph search failed: " + e.getMessage()
            );
        }
    }

    private String listGraphSpaces(JsonNode arguments, RuntimeContext runtimeContext)
            throws JsonProcessingException {
        PageResponse<GraphSpaceReference> page = listRegisteredGraphSpaces(
                runtimeContext.tenantId(),
                settings.capLimit(integer(arguments, "limit")),
                text(arguments, "cursor", "")
        );
        if (page.items().isEmpty()) {
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
            return "No readable graph spaces were found.";
        }
        ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
        return "[Structured Knowledge Graph Spaces]\n"
                + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(page);
    }

    private PageResponse<GraphSpaceReference> listRegisteredGraphSpaces(
            String tenantId,
            int limit,
            String cursor
    ) {
        List<GraphSpaceReference> items = new ArrayList<>(limit);
        String currentCursor = cursor;
        PageInfo pageInfo;
        do {
            int remaining = limit - items.size();
            PageResponse<GraphSpaceReference> page = graphSpaceAccessService.listReadable(
                    tenantId, remaining, currentCursor);
            page.items().stream()
                    .filter(space -> schemaRegistry.find(space.schemaId()).isPresent())
                    .forEach(items::add);
            pageInfo = page.pageInfo();
            if (!pageInfo.hasMore() || items.size() >= limit) {
                break;
            }
            if (pageInfo.nextCursor().isBlank()
                    || pageInfo.nextCursor().equals(currentCursor)) {
                throw new IllegalStateException(
                        "Graph-space pagination did not advance its cursor"
                );
            }
            currentCursor = pageInfo.nextCursor();
        } while (items.size() < limit);

        return new PageResponse<>(List.copyOf(items), new PageInfo(
                limit,
                pageInfo.nextCursor(),
                pageInfo.hasMore()
        ));
    }

    private String findNodes(JsonNode arguments, RuntimeContext runtimeContext)
            throws JsonProcessingException {
        GraphRequestContext serverContext = runtimeContext.requestContext();
        String graphId;
        String schemaId;
        if (serverContext == null) {
            graphId = requiredText(arguments, "graphId");
            schemaId = requiredText(arguments, "schemaId");
        } else {
            requireMatchingIdentifier(arguments, "graphId", serverContext.graphId());
            requireMatchingIdentifier(arguments, "schemaId", serverContext.schemaId());
            graphId = serverContext.graphId();
            schemaId = serverContext.schemaId();
        }
        graphSpaceAccessService.requireReadable(
                runtimeContext.tenantId(),
                graphId,
                schemaId
        );
        schemaRegistry.require(schemaId);

        var page = graphStore.listNodes(new GraphNodePageRequest(
                graphId,
                schemaId,
                text(arguments, "label", ""),
                text(arguments, "name", ""),
                settings.capLimit(integer(arguments, "limit")),
                text(arguments, "cursor", "")
        ));
        if (page.items().isEmpty()) {
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
            return "No graph nodes matched the requested filters.";
        }

        GraphRouteResult result = new GraphRouteResult(
                page.items(), List.of(), List.of(), List.of(), page.pageInfo(), java.util.Map.of());
        ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
        return formatGraphResult(schemaId, result)
                + "- pageInfo=" + objectMapper.writeValueAsString(page.pageInfo()) + "\n";
    }

    private String findNeighborhood(JsonNode arguments, RuntimeContext runtimeContext) {
        GraphRequestContext serverContext = runtimeContext.requestContext();
        String queryId = text(
                arguments,
                "queryId",
                AnchoredNeighborhoodGraphRetriever.QUERY_ID
        );
        GraphRequestContext effectiveContext;
        if (serverContext != null) {
            requireMatchingIdentifier(arguments, "graphId", serverContext.graphId());
            requireMatchingIdentifier(arguments, "schemaId", serverContext.schemaId());
            graphSpaceAccessService.requireReadable(
                    runtimeContext.tenantId(),
                    serverContext.graphId(),
                    serverContext.schemaId()
            );
            Set<String> requestedSubjects = stringSet(arguments, "subjectIds");
            Set<String> effectiveSubjects;
            if (serverContext.hasSubjectScope()) {
                if (!requestedSubjects.isEmpty()
                        && !serverContext.subjectIds().containsAll(requestedSubjects)) {
                    throw new SecurityException(
                            "Requested subjectIds exceed the server-authorized graph scope"
                    );
                }
                effectiveSubjects = requestedSubjects.isEmpty()
                        ? serverContext.subjectIds()
                        : requestedSubjects;
            } else {
                if (requestedSubjects.isEmpty()) {
                    throw new IllegalArgumentException(
                            "subjectIds is required after findNodes"
                    );
                }
                effectiveSubjects = requestedSubjects;
            }
            effectiveContext = new GraphRequestContext(
                    serverContext.graphId(),
                    serverContext.schemaId(),
                    effectiveSubjects,
                    serverContext.allowedQueryIds()
            );
        } else {
            String graphId = requiredText(arguments, "graphId");
            String schemaId = requiredText(arguments, "schemaId");
            Set<String> subjectIds = stringSet(arguments, "subjectIds");
            if (subjectIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "subjectIds is required for findNeighborhood; use findNodes first"
                );
            }
            graphSpaceAccessService.requireReadable(
                    runtimeContext.tenantId(),
                    graphId,
                    schemaId
            );
            effectiveContext = new GraphRequestContext(
                    graphId,
                    schemaId,
                    subjectIds,
                    Set.of(AnchoredNeighborhoodGraphRetriever.QUERY_ID)
            );
        }

        GraphRouteResult result = retriever.retrieve(
                effectiveContext,
                queryId,
                stringSet(arguments, "relationTypes"),
                integer(arguments, "maxDepth"),
                integer(arguments, "limit")
        );
        if (result.isEmpty()) {
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
            return "No structured graph records were found for the requested subjects.";
        }
        ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
        return formatGraphResult(effectiveContext.schemaId(), result);
    }

    private static void requireAllowedAction(
            GraphRequestContext serverContext,
            String action
    ) {
        if (serverContext == null) {
            return;
        }
        if (serverContext.hasSubjectScope()) {
            if (!ACTION_FIND_NEIGHBORHOOD.equals(action)) {
                throw new SecurityException(
                        "Subject-scoped graph retrieval only allows findNeighborhood"
                );
            }
            return;
        }
        if (ACTION_LIST_GRAPH_SPACES.equals(action)) {
            throw new SecurityException(
                    "Graph-space-scoped retrieval does not allow listGraphSpaces"
            );
        }
    }

    private String formatGraphResult(String schemaId, GraphRouteResult result) {
        return new DefaultGraphResultFormatter(
                schemaRegistry.require(schemaId),
                settings,
                objectMapper
        ).format(result);
    }

    private ObjectNode stringProperty(String description) {
        return objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", description);
    }

    private ObjectNode arrayProperty(String description) {
        return objectMapper.createObjectNode()
                .put("type", "array")
                .put("description", description)
                .set("items", objectMapper.createObjectNode().put("type", "string"));
    }

    private static String requiredText(JsonNode arguments, String name) {
        String value = text(arguments, name, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String text(JsonNode arguments, String name, String defaultValue) {
        if (arguments == null || !arguments.hasNonNull(name) || arguments.get(name).asText().isBlank()) {
            return defaultValue;
        }
        return arguments.get(name).asText().trim();
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
                values.add(value.asText().trim());
            }
        });
        return Set.copyOf(values);
    }

    private static void requireMatchingIdentifier(
            JsonNode arguments,
            String name,
            String authorizedValue
    ) {
        String requestedValue = text(arguments, name, "");
        if (!requestedValue.isEmpty() && !authorizedValue.equals(requestedValue)) {
            throw new SecurityException(name + " exceeds the server-authorized graph scope");
        }
    }

    private static String canonicalArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return "null";
        }
        if (arguments.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            arguments.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            StringBuilder result = new StringBuilder("{");
            for (String fieldName : fieldNames) {
                if ("action".equals(fieldName)) {
                    continue;
                }
                result.append(fieldName)
                        .append(':')
                        .append(canonicalArguments(arguments.get(fieldName)))
                        .append(';');
            }
            return result.append('}').toString();
        }
        if (arguments.isArray()) {
            List<String> values = new ArrayList<>();
            arguments.forEach(value -> values.add(canonicalArguments(value)));
            Collections.sort(values);
            return "[" + String.join(",", values) + "]";
        }
        return arguments.toString();
    }

    private static final class RuntimeContext {

        private final String tenantId;
        private final GraphRequestContext requestContext;
        private final Set<String> invocationKeys = new HashSet<>();

        private RuntimeContext(String tenantId, GraphRequestContext requestContext) {
            this.tenantId = tenantId;
            this.requestContext = requestContext;
        }

        private String tenantId() {
            return tenantId;
        }

        private GraphRequestContext requestContext() {
            return requestContext;
        }

        private void requireFreshInvocation(String action, String canonicalArguments) {
            String invocationKey = action + ':' + canonicalArguments;
            if (!invocationKeys.add(invocationKey)) {
                throw new ToolExecutionException(
                        TOOL_NAME,
                        "An identical knowledge graph call already ran in this Agent request; "
                                + "use its result or change the query parameters"
                );
            }
        }
    }

    record ContextSnapshot(
            String tenantId,
            GraphRequestContext requestContext
    ) {
    }
}
