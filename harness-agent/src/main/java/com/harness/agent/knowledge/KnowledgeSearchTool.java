package com.harness.agent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeSearchOptions;
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
                        + "conversation. When the user refers to an earlier conversation, do not copy "
                        + "their wording: rewrite it into a self-contained query that names what was "
                        + "actually discussed. \"remember the Redis thing\" becomes \"the user's earlier "
                        + "discussion of Redis, local caching, cache architecture, and the tradeoffs "
                        + "between Redis and a local cache\". Add synonyms, the English term, and the "
                        + "domain vocabulary the source material would use, but never invent facts the "
                        + "user did not state."));
        ObjectNode types = objectMapper.createObjectNode()
                .put("type", "array")
                .put("description", "Optional filter to restrict the search to specific kinds. "
                        + "SOURCE_DOCUMENT = uploaded domain documents; "
                        + "OPERATION_PLAYBOOK = how this agent handled similar tasks; "
                        + "USER_EPISODE = what happened with this user in past sessions; "
                        + "GRAPH_SCHEMA = the graph Schema description. "
                        + "USER_PREFERENCE is injected automatically before the model call and cannot be searched. "
                        + "Omit to search the unified Wiki index.");
        ObjectNode typeItems = objectMapper.createObjectNode().put("type", "string");
        var typeEnum = typeItems.putArray("enum");
        for (KnowledgeConceptType type : KnowledgeConceptType.values()) {
            if (type != KnowledgeConceptType.USER_PREFERENCE
                    && type != KnowledgeConceptType.GRAPH_SPACE) {
                typeEnum.add(type.name());
            }
        }
        types.set("items", typeItems);
        types.put("uniqueItems", true);
        properties.set("knowledgeTypes", types);
        properties.set("limit", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1).put("maximum", 20));
        properties.set("candidateTopK", objectMapper.createObjectNode().put("type", "integer")
                .put("minimum", 1).put("maximum", 100).put("default", 20)
                .put("description", "Candidates per retrieval lane; must be at least limit."));
        properties.set("bm25Weight", objectMapper.createObjectNode().put("type", "number")
                .put("minimum", 0).put("maximum", 1).put("default", 0.5)
                .put("description", "Weighted RRF: 0 = vector only, 1 = keyword only; vector weight is 1 minus this."));
        properties.set("rerank", objectMapper.createObjectNode().put("type", "boolean").put("default", true)
                .put("description", "Rerank document candidates when a reranker is configured."));
        properties.set("recent", objectMapper.createObjectNode()
                .put("type", "boolean")
                .put("description", "Set true only for a question about the history itself that names "
                        + "no subject — \"what did I ask before?\", \"what have we discussed lately?\". "
                        + "It lists this user's most recent episodes in time order instead of searching "
                        + "semantically, so a subject-bearing query must leave it unset. When true, "
                        + "knowledgeTypes may only be USER_EPISODE."));
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
                        + "— the graph Schema description. "
                        + "Search before answering whenever the request could depend on earlier sessions, "
                        + "uploaded material, or known entity relations — including when the user did not "
                        + "explicitly ask for a search. "
                        + "For anything the user asked or decided earlier, rewrite their phrasing into a "
                        + "self-contained query built from the actual subject before searching; a verbatim "
                        + "copy of \"that Redis thing\" matches nothing. "
                        + "A question with no subject at all (\"what did I ask before?\") cannot be matched "
                        + "semantically — use recent=true for those. "
                        + "Choose weights and candidateTopK per search; adjust them and retry when evidence is insufficient. Scores retain route-specific semantics. Use knowledge_read for source details. "
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
            boolean recent = bool(arguments, "recent");
            if (recent) {
                for (String field : List.of("candidateTopK", "bm25Weight", "denseThreshold", "sparseThreshold", "rerank")) {
                    if (arguments.has(field)) throw new IllegalArgumentException(field + " does not apply to recent=true");
                }
            }
            if (arguments.has("denseThreshold") || arguments.has("sparseThreshold")) {
                throw new IllegalArgumentException("Retrieval thresholds are configured by the server");
            }
            KnowledgeSearchOptions options = KnowledgeSearchOptions.configured(limit,
                    integer(arguments, "candidateTopK", 20), number(arguments, "bm25Weight", 0.5),
                    !arguments.has("rerank") || bool(arguments, "rerank"));
            Set<KnowledgeConceptType> requestedTypes = requestedTypes(arguments, recent);
            KnowledgeToolRuntimeContext context =
                    KnowledgeToolRuntimeContext.requireCurrent(TOOL_NAME);
            List<DiscoveredKnowledge> discovered = recent
                    ? router.recentEpisodes(limit, context)
                    : router.search(query, requestedTypes, options, context);
            List<Hit> hits = discovered.stream()
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
                            hit.graphRouteHint(),
                            hit.eventTime() == null ? null : hit.eventTime().toString()))
                    .toList();
            Map<String, Object> meta = recent
                    ? Map.of("scorePolicy", "recency-order")
                    : Map.of("scorePolicy", "route-specific", "retrieval", options, "fusion", "weightedRrf",
                            "rrfK", KnowledgeSearchOptions.RRF_K, "documentRerankAvailable", router.rerankAvailable(),
                            "returnedCount", hits.size());
            ToolEnvelope<SearchData> envelope = hits.isEmpty()
                    ? ToolEnvelope.empty(new SearchData(hits), null, meta)
                    : ToolEnvelope.success(new SearchData(hits), null, meta);
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

    private static Set<KnowledgeConceptType> requestedTypes(JsonNode arguments, boolean recent) {
        JsonNode values = arguments == null ? null : arguments.get("knowledgeTypes");
        if (values == null || values.isNull()) {
            return recent ? Set.of(KnowledgeConceptType.USER_EPISODE) : Set.of();
        }
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
        if (recent && !types.equals(Set.of(KnowledgeConceptType.USER_EPISODE))) {
            // Recall walks USER_EPISODE by time, so another kind silently cannot be served.
            throw new IllegalArgumentException(
                    "recent=true only applies to USER_EPISODE; drop knowledgeTypes or set it to "
                            + "[USER_EPISODE]");
        }
        return Set.copyOf(types);
    }

    private static double number(JsonNode arguments, String field, double defaultValue) {
        JsonNode value = arguments.get(field);
        if (value == null) return defaultValue;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return value.doubleValue();
    }

    private static boolean bool(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.booleanValue();
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
            Map<String, Object> graphRouteHint,
            // ISO-8601 event time, on User Episode hits only. A string rather than an Instant so
            // the envelope serializes without depending on the mapper's Java-time module.
            String eventTime
    ) {
    }
}
