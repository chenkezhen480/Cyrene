package com.harness.agent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeHandleCodec;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.protocol.ToolEnvelope;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Model-visible unified discovery tool. */
public final class KnowledgeSearchTool implements Tool {

    public static final String TOOL_NAME = "knowledge_search";

    private final KnowledgeDiscoveryRouter router;
    private final KnowledgeHandleCodec handleCodec;
    private final ObjectMapper objectMapper;

    public KnowledgeSearchTool(
            KnowledgeDiscoveryRouter router,
            KnowledgeHandleCodec handleCodec,
            ObjectMapper objectMapper
    ) {
        this.router = java.util.Objects.requireNonNull(router, "router");
        this.handleCodec = java.util.Objects.requireNonNull(handleCodec, "handleCodec");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ToolSpec spec() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("query", objectMapper.createObjectNode()
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", 4096)
                .put("description", "Complete standalone knowledge query in natural language. Include the "
                        + "entity names and enough context to stand alone — the index does not see this "
                        + "conversation."));
        ObjectNode types = objectMapper.createObjectNode()
                .put("type", "array")
                .put("description", "Optional filter to restrict the search to specific kinds. "
                        + "SOURCE_DOCUMENT = uploaded domain documents; "
                        + "OPERATION_PLAYBOOK = how this agent handled similar tasks; "
                        + "USER_EPISODE = what happened with this user in past sessions; "
                        + "GRAPH_SCHEMA / GRAPH_SPACE = business entity relations. "
                        + "Omit to search the unified Wiki index.");
        ObjectNode typeItems = objectMapper.createObjectNode().put("type", "string");
        var typeEnum = typeItems.putArray("enum");
        for (KnowledgeConceptType type : KnowledgeConceptType.values()) {
            if (type != KnowledgeConceptType.USER_PREFERENCE) typeEnum.add(type.name());
        }
        types.set("items", typeItems);
        types.put("uniqueItems", true);
        properties.set("knowledgeTypes", types);
        properties.set("limit", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 20));
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);
        return new ToolSpec(
                TOOL_NAME,
                "Search one unified index holding everything this agent has accumulated. It covers four "
                        + "kinds of material: (1) OPERATION_PLAYBOOK — how similar tasks were handled before, "
                        + "including step order and pitfalls; (2) USER_EPISODE — what happened with this user "
                        + "in earlier sessions, including decisions and outcomes; (3) SOURCE_DOCUMENT — uploaded "
                        + "domain documents and the professional knowledge extracted from them; (4) GRAPH_SCHEMA "
                        + "and GRAPH_SPACE — business entity relations the graph can answer. "
                        + "Search before answering whenever the request could depend on earlier sessions, "
                        + "uploaded material, or known entity relations — including when the user did not "
                        + "explicitly ask for a search. "
                        + "Scores retain route-specific semantics. Use knowledge_read for source details. "
                        + "Graph hits are capability/Schema cards, not graph facts: "
                        + "use graphRouteHint.recommendedTool (query_graph) with the discovered graphId/schemaId to query Neo4j.",
                schema,
                com.harness.core.model.ToolCapability.RETRIEVAL);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        try {
            String query = requiredText(arguments, "query");
            int limit = integer(arguments, "limit", 10);
            Set<KnowledgeConceptType> requestedTypes = requestedTypes(arguments);
            KnowledgeToolRuntimeContext context =
                    KnowledgeToolRuntimeContext.requireCurrent(TOOL_NAME);
            List<Hit> hits = router.search(query, requestedTypes, limit, context).stream()
                    .map(hit -> new Hit(
                            hit.knowledgeKind(),
                            hit.conceptId(),
                            hit.currentRevisionId(),
                            hit.routeTarget(),
                            handleCodec.encode(hit.handle()),
                            hit.title(),
                            hit.summary(),
                            hit.scoreType(),
                            hit.score(),
                            hit.sourceAnchors(),
                            hit.graphRouteHint()))
                    .toList();
            ToolEnvelope<SearchData> envelope = hits.isEmpty()
                    ? ToolEnvelope.empty(new SearchData(hits), null,
                            Map.of("scorePolicy", "route-specific"))
                    : ToolEnvelope.success(new SearchData(hits), null,
                            Map.of("scorePolicy", "route-specific"));
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text(objectMapper.writeValueAsString(envelope)),
                    hits.isEmpty() ? ResultStatus.EMPTY : ResultStatus.AVAILABLE);
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolExecutionException(
                    TOOL_NAME, "Knowledge discovery failed: " + exception.getMessage());
        }
    }

    private static Set<KnowledgeConceptType> requestedTypes(JsonNode arguments) {
        JsonNode values = arguments == null ? null : arguments.get("knowledgeTypes");
        if (values == null || values.isNull()) return Set.of();
        if (!values.isArray()) {
            throw new IllegalArgumentException("knowledgeTypes must be an array");
        }
        LinkedHashSet<KnowledgeConceptType> types = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("knowledgeTypes must contain strings");
            }
            KnowledgeConceptType type = KnowledgeConceptType.valueOf(value.asText());
            if (type == KnowledgeConceptType.USER_PREFERENCE) {
                throw new IllegalArgumentException(
                        "USER_PREFERENCE is activated automatically and is not searchable");
            }
            types.add(type);
        });
        return Set.copyOf(types);
    }

    private static String requiredText(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText().trim();
    }

    private static int integer(JsonNode arguments, String field, int defaultValue) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    public record SearchData(List<Hit> hits) {
        public SearchData {
            hits = List.copyOf(hits == null ? List.of() : hits);
        }
    }

    public record Hit(
            KnowledgeConceptType knowledgeKind,
            String conceptId,
            String currentRevisionId,
            com.harness.core.knowledge.KnowledgeRouteTarget routeTarget,
            String handle,
            String title,
            String summary,
            String scoreType,
            double score,
            List<Map<String, Object>> sourceAnchors,
            Map<String, Object> graphRouteHint
    ) {
    }
}
