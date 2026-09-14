package com.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.agent.graph.GraphSpaceReference;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.model.GraphNodePageRequest;
import com.harness.graph.model.GraphRouteResult;
import com.harness.graph.retrieval.AnchoredNeighborhoodGraphRetriever;
import com.harness.graph.retrieval.DefaultGraphResultFormatter;
import com.harness.graph.retrieval.GraphKnowledgeRetriever;
import com.harness.graph.retrieval.GraphToolData;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.tool.protocol.ToolEnvelope;
import com.harness.tool.Tool;
import com.harness.tool.protocol.ToolEnvelopeStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structured graph discovery and retrieval tool.
 *
 * <p>A server-provided request scope always takes precedence. Without one, the tool can discover
 * graph spaces and nodes autonomously, subject to the configured graph-space access service.</p>
 */
public final class KnowledgeGraphTool implements Tool {

    public static final String TOOL_NAME = "query_graph";
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
    private final com.harness.core.structured.StructuredOutputValueValidator argumentValidator;

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
        this.argumentValidator = new com.harness.core.structured.StructuredOutputValueValidator(objectMapper);
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
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode action = objectMapper.createObjectNode().put("type", "string");
        action.putArray("enum").add(ACTION_LIST_GRAPH_SPACES)
                .add(ACTION_FIND_NODES).add(ACTION_FIND_NEIGHBORHOOD);
        properties.set("action", action);
        for (String field : List.of("graphId", "schemaId", "name", "label", "cursor", "queryId")) {
            properties.set(field, objectMapper.createObjectNode().put("type", "string"));
        }
        for (String field : List.of("subjectIds", "relationTypes")) {
            properties.set(field, objectMapper.createObjectNode().put("type", "array")
                    .set("items", objectMapper.createObjectNode().put("type", "string")));
        }
        properties.set("maxDepth", objectMapper.createObjectNode().put("type", "integer")
                .put("minimum", 1).put("maximum", settings.maxDepth()));
        properties.set("limit", objectMapper.createObjectNode().put("type", "integer")
                .put("minimum", 1).put("maximum", settings.maxLimit()));
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        schema.set("properties", properties);
        schema.putArray("required");
        return new ToolSpec(TOOL_NAME,
                "Query Neo4j entities and relationships directly, without a Wiki handle. "
                        + "For entity membership, related entities or bounded relationship paths, use this tool. "
                        + "Without graphId/schemaId, listGraphSpaces discovers readable graphs and their Schema cards; "
                        + "then findNodes by name/label and findNeighborhood with returned subjectIds. "
                        + "Wiki graph hits describe capabilities only and may recommend this tool. "
                        + "Use returned cursors for list pagination; never supply Cypher or invent identifiers. "
                        + "Trusted server graph, subject and query scopes cannot be widened.",
                schema, com.harness.core.model.ToolCapability.RETRIEVAL);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        if (runtimeContext == null) {
            throw new ToolExecutionException(
                    TOOL_NAME, "No graph tool runtime context is available");
        }
        try {
            validateArguments(arguments);
            GraphRequestContext serverContext = runtimeContext.requestContext();
            String schemaId = text(arguments, "schemaId", "");
            String graphId = text(arguments, "graphId", "");
            String defaultAction = serverContext == null
                    ? !stringSet(arguments, "subjectIds").isEmpty() ? ACTION_FIND_NEIGHBORHOOD
                            : graphId.isEmpty() ? ACTION_LIST_GRAPH_SPACES : ACTION_FIND_NODES
                    : serverContext.hasSubjectScope()
                            ? ACTION_FIND_NEIGHBORHOOD
                            : ACTION_FIND_NODES;
            String action = text(arguments, "action", defaultAction);
            requireAllowedAction(serverContext, action);
            runtimeContext.requireFreshInvocation(
                    action, canonicalArguments(arguments));
            return switch (action) {
                case ACTION_LIST_GRAPH_SPACES -> listGraphSpaces(
                        arguments, runtimeContext, schemaId.isEmpty() ? null : schemaId);
                case ACTION_FIND_NODES -> findNodes(arguments, runtimeContext);
                case ACTION_FIND_NEIGHBORHOOD ->
                        findNeighborhood(arguments, runtimeContext);
                default -> throw new IllegalArgumentException(
                        "Unsupported graph action: " + action);
            };
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Knowledge graph query failed: " + exception.getMessage());
        }
    }

    /** Returns only requested Schema IDs that have a graph space readable in this request. */
    public Set<String> readableWikiSchemas(String tenantId, Set<String> schemaIds) {
        Set<String> requested = Set.copyOf(
                Objects.requireNonNull(schemaIds, "schemaIds"));
        if (requested.isEmpty()) return Set.of();
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        if (runtimeContext == null) {
            throw new ToolExecutionException(
                    TOOL_NAME, "No graph tool runtime context is available");
        }
        GraphRequestContext trusted = runtimeContext.requestContext();
        if (trusted != null) {
            if (!requested.contains(trusted.schemaId())) return Set.of();
            graphSpaceAccessService.requireReadable(
                    tenantId, trusted.graphId(), trusted.schemaId());
            return Set.of(trusted.schemaId());
        }

        LinkedHashSet<String> readable = new LinkedHashSet<>();
        String cursor = "";
        while (true) {
            PageResponse<GraphSpaceReference> page = graphSpaceAccessService.listReadable(
                    tenantId, settings.maxLimit(), cursor);
            page.items().stream()
                    .map(GraphSpaceReference::schemaId)
                    .filter(requested::contains)
                    .filter(schemaId -> schemaRegistry.find(schemaId).isPresent())
                    .forEach(readable::add);
            if (readable.containsAll(requested) || !page.pageInfo().hasMore()) {
                return Set.copyOf(readable);
            }
            String nextCursor = page.pageInfo().nextCursor();
            if (nextCursor.isBlank() || nextCursor.equals(cursor)) {
                throw new IllegalStateException(
                        "Graph-space pagination did not advance its cursor");
            }
            cursor = nextCursor;
        }
    }

    /** Returns only candidate Concept IDs whose exact graphId/schemaId pair is readable. */
    public Set<String> readableWikiGraphSpaces(
            String tenantId,
            Map<String, GraphSpaceReference> candidates
    ) {
        Map<String, GraphSpaceReference> requested = Map.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
        if (requested.isEmpty()) return Set.of();
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        if (runtimeContext == null) {
            throw new ToolExecutionException(
                    TOOL_NAME, "No graph tool runtime context is available");
        }
        GraphRequestContext trusted = runtimeContext.requestContext();
        LinkedHashSet<String> readable = new LinkedHashSet<>();
        for (Map.Entry<String, GraphSpaceReference> entry : requested.entrySet()) {
            GraphSpaceReference space = entry.getValue();
            if (trusted != null && (!space.graphId().equals(trusted.graphId())
                    || !space.schemaId().equals(trusted.schemaId()))) {
                continue;
            }
            try {
                graphSpaceAccessService.requireReadable(
                        tenantId, space.graphId(), space.schemaId());
                readable.add(entry.getKey());
            } catch (SecurityException ignored) {
                // A bounded candidate is omitted when the current tenant cannot read it.
            }
        }
        return Set.copyOf(readable);
    }

    private ToolExecutionOutcome listGraphSpaces(
            JsonNode arguments,
            RuntimeContext runtimeContext,
            String schemaId
    ) {
        PageResponse<GraphSpaceReference> page = listRegisteredGraphSpaces(
                runtimeContext.tenantId(),
                settings.capLimit(integer(arguments, "limit")),
                text(arguments, "cursor", ""),
                schemaId
        );
        ToolEnvelope<GraphSpacesData> envelope = page.items().isEmpty()
                ? ToolEnvelope.empty(
                        new GraphSpacesData(List.of(), Map.of()),
                        page.pageInfo(),
                        Map.of("truncated", false))
                : ToolEnvelope.success(
                        new GraphSpacesData(page.items(), page.items().stream().collect(
                                java.util.stream.Collectors.toMap(GraphSpaceReference::schemaId,
                                        space -> schemaRegistry.find(space.schemaId()).orElseThrow(),
                                        (first, duplicate) -> first))),
                        page.pageInfo(),
                        Map.of("truncated", false));
        return graphOutcome(envelope);
    }

    private PageResponse<GraphSpaceReference> listRegisteredGraphSpaces(
            String tenantId,
            int limit,
            String cursor,
            String schemaId
    ) {
        List<GraphSpaceReference> items = new ArrayList<>(limit);
        String currentCursor = cursor;
        PageInfo pageInfo;
        do {
            int remaining = limit - items.size();
            PageResponse<GraphSpaceReference> page = graphSpaceAccessService.listReadable(
                    tenantId, remaining, currentCursor);
            page.items().stream()
                    .filter(space -> schemaId == null || schemaId.equals(space.schemaId()))
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

    private ToolExecutionOutcome findNodes(JsonNode arguments, RuntimeContext runtimeContext) {
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
        GraphRouteResult result = new GraphRouteResult(
                page.items(), List.of(), List.of(), List.of(), page.pageInfo(), java.util.Map.of());
        ToolEnvelope<GraphToolData> envelope = formatGraphResult(graphId, schemaId, result);
        return graphOutcome(envelope);
    }

    private ToolExecutionOutcome findNeighborhood(JsonNode arguments, RuntimeContext runtimeContext) {
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
        ToolEnvelope<GraphToolData> envelope = formatGraphResult(
                effectiveContext.graphId(), effectiveContext.schemaId(), result);
        return graphOutcome(envelope);
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

    private ToolEnvelope<GraphToolData> formatGraphResult(
            String graphId, String schemaId, GraphRouteResult result) {
        return new DefaultGraphResultFormatter(
                schemaRegistry.require(schemaId),
                settings,
                objectMapper
        ).format(graphId, result);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize graph tool result", e);
        }
    }

    private ToolExecutionOutcome graphOutcome(ToolEnvelope<?> envelope) {
        ResultStatus resultStatus = envelope.status() == ToolEnvelopeStatus.EMPTY
                ? ResultStatus.EMPTY
                : ResultStatus.AVAILABLE;
        return ToolExecutionOutcome.succeeded(
                ToolOutput.text(serialize(envelope)), resultStatus);
    }

    private void validateArguments(JsonNode arguments) {
        try {
            argumentValidator.validate(arguments, spec().parameters());
        } catch (com.harness.core.exception.StructuredOutputException exception) {
            throw new IllegalArgumentException("Invalid graph arguments: " + exception.details(), exception);
        }
        Map.of("limit", settings.maxLimit(), "maxDepth", settings.maxDepth()).forEach((field, maximum) -> {
            JsonNode value = arguments.get(field);
            if (value != null && (!value.canConvertToInt() || value.intValue() < 1 || value.intValue() > maximum)) {
                throw new IllegalArgumentException("Invalid graph arguments: " + field + " exceeds configured bounds");
            }
        });
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

    private record GraphSpacesData(List<GraphSpaceReference> graphSpaces,
                                   Map<String, com.harness.graph.schema.GraphSchemaDefinition> schemas) {
        private GraphSpacesData {
            graphSpaces = List.copyOf(graphSpaces);
            schemas = Map.copyOf(schemas);
        }
    }
}
