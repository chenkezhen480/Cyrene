package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.knowledge.KnowledgeSearchTool;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.knowledge.*;
import com.harness.core.model.*;
import com.harness.tool.Tool;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.protocol.ToolEnvelope;
import java.time.Clock;
import java.util.*;

/** Test-only ablation: original chunks replace semantic catalog selection. */
final class RawChunkKnowledgeSearch implements Tool {
    private final ToolSpec spec;
    private final KnowledgeRepository repository;
    private final KnowledgeAccessService access;
    private final KnowledgeHandleCodec codec;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String collection;

    RawChunkKnowledgeSearch(ToolSpec spec, KnowledgeRepository repository,
            KnowledgeAccessService access, KnowledgeHandleCodec codec, ObjectMapper mapper,
            Clock clock, String collection) {
        this.spec = spec;
        this.repository = repository;
        this.access = access;
        this.codec = codec;
        this.mapper = mapper;
        this.clock = clock;
        this.collection = collection;
    }

    @Override public ToolSpec spec() { return spec; }
    @Override public String execute(JsonNode arguments) { return executeOutcome(arguments).content().modelContent(); }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        try {
            KnowledgeToolRuntimeContext scope = KnowledgeToolRuntimeContext.requireCurrent(KnowledgeSearchTool.TOOL_NAME);
            if (!scope.authorizedTools().contains(KnowledgeSearchTool.TOOL_NAME)) throw new SecurityException("Search is not authorized");
            String query = arguments.path("query").asText().trim();
            if (query.isEmpty() || query.length() > 4096) throw new IllegalArgumentException("query must contain 1 to 4096 characters");
            if (arguments.has("denseThreshold") || arguments.has("sparseThreshold")) throw new IllegalArgumentException("Retrieval thresholds are configured by the server");
            KnowledgeSearchOptions options = KnowledgeSearchOptions.configured(integer(arguments, "limit", 10),
                    integer(arguments, "candidateTopK", 20), number(arguments, "bm25Weight", 0.5), bool(arguments, "rerank", true));
            boolean sourceRequested = !arguments.has("knowledgeTypes") || arguments.path("knowledgeTypes").isEmpty();
            if (arguments.has("knowledgeTypes") && !arguments.get("knowledgeTypes").isArray()) throw new IllegalArgumentException("knowledgeTypes must be an array");
            for (JsonNode type : arguments.path("knowledgeTypes")) {
                if (!type.isTextual()) throw new IllegalArgumentException("knowledgeTypes must contain strings");
                KnowledgeConceptType kind = KnowledgeConceptType.valueOf(type.asText());
                if (kind == KnowledgeConceptType.USER_PREFERENCE) throw new IllegalArgumentException("USER_PREFERENCE cannot be searched");
                sourceRequested |= kind == KnowledgeConceptType.SOURCE_DOCUMENT;
            }
            Map<String, KnowledgeHead> heads = sourceRequested && !bool(arguments, "recent", false)
                    ? findDocuments(scope) : Map.of();
            List<KnowledgeSearchTool.Hit> hits = new ArrayList<>();
            if (!heads.isEmpty()) {
                Map<String, String> revisions = new LinkedHashMap<>();
                heads.forEach((id, head) -> revisions.put(id, head.currentVersion()));
                var result = access.searchDocumentRevisions(query, collection, options, revisions);
                Map<String, KnowledgeHead> current = eligibleDocuments(new ArrayList<>(repository.findAuthorityByIds(
                        result.documents().stream().map(document -> String.valueOf(document.metadata().get("document_id"))).distinct().toList()).values()),
                        scope, collection, clock);
                Set<String> returned = new HashSet<>();
                for (var document : result.documents()) {
                    String id = String.valueOf(document.metadata().get("document_id"));
                    String revision = String.valueOf(document.metadata().get("revision_id"));
                    KnowledgeHead head = current.get(id);
                    if (head == null || !heads.containsKey(id) || !head.currentVersion().equals(revision)
                            || document.chunkIndex() < 0 || !returned.add(id)) continue;
                    String summary = document.content().strip();
                    if (summary.length() > 400) summary = summary.substring(0, 400) + "…";
                    hits.add(new KnowledgeSearchTool.Hit(KnowledgeConceptType.SOURCE_DOCUMENT, id, revision,
                            KnowledgeRouteTarget.DOCUMENT, codec.encode(KnowledgeHandle.document(
                            KnowledgeConceptType.SOURCE_DOCUMENT, id, revision, collection, id, document.chunkIndex())),
                            head.currentRevision().title(), summary, result.metadata().get("scoreType"), document.score(),
                            List.of(Map.of("documentId", id, "revisionId", revision, "chunkIndex", document.chunkIndex())), Map.of(), null));
                    if (hits.size() >= options.limit()) break;
                }
            }
            if (scope.runTrace() != null) scope.runTrace().putMetadata(Map.of(
                    "knowledge_search_mode", "raw_original_chunks", "knowledge_search_query", query,
                    "knowledge_search_authorized_hits", String.valueOf(heads.size()),
                    "knowledge_search_final_hits", String.valueOf(hits.size())));
            var data = new KnowledgeSearchTool.SearchData(hits);
            var metadata = Map.<String, Object>of("scorePolicy", "route-specific", "retrieval", options,
                    "fusion", "weightedRrf", "rrfK", KnowledgeSearchOptions.RRF_K,
                    "documentRerankAvailable", access.rerankAvailable(), "returnedCount", hits.size(),
                    "experimentCondition", "wiki_off");
            var envelope = hits.isEmpty() ? ToolEnvelope.empty(data, null, metadata) : ToolEnvelope.success(data, null, metadata);
            return ToolExecutionOutcome.succeeded(ToolOutput.text(mapper.writeValueAsString(envelope)),
                    hits.isEmpty() ? ResultStatus.EMPTY : ResultStatus.AVAILABLE);
        } catch (ToolExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolExecutionException(KnowledgeSearchTool.TOOL_NAME, "Raw experiment search failed: " + exception.getMessage());
        }
    }

    private Map<String, KnowledgeHead> findDocuments(KnowledgeToolRuntimeContext scope) {
        List<KnowledgeHead> heads = new ArrayList<>();
        for (String tenant : scope.tenantId() == null ? Collections.<String>singletonList(null) : Arrays.asList(scope.tenantId(), null)) {
            KnowledgeConceptCursor cursor = null;
            while (true) {
                var page = repository.findPage(tenant, null, KnowledgeNamespaceType.COLLECTION,
                        KnowledgeConceptType.SOURCE_DOCUMENT, KnowledgeStatus.STABLE, cursor, 100);
                heads.addAll(repository.findAuthorityByIds(page.items().stream().map(KnowledgeConcept::id).toList()).values());
                if (!page.pageInfo().hasMore()) break;
                if (page.items().isEmpty()) throw new IllegalStateException("Knowledge page hasMore without items");
                var last = page.items().getLast();
                cursor = new KnowledgeConceptCursor(last.updatedAt(), last.id());
            }
        }
        return eligibleDocuments(heads, scope, collection, clock);
    }

    static Map<String, KnowledgeHead> eligibleDocuments(List<KnowledgeHead> heads,
            KnowledgeToolRuntimeContext scope, String collection, Clock clock) {
        Map<String, KnowledgeHead> eligible = new LinkedHashMap<>();
        for (KnowledgeHead head : heads) {
            var concept = head.concept();
            if (head.currentRevision() == null || concept.conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT
                    || concept.status() != KnowledgeStatus.STABLE || concept.isStaleAt(clock.instant())
                    || concept.userId() != null || concept.tenantId() != null && !Objects.equals(concept.tenantId(), scope.tenantId())
                    || !Objects.equals(collection, head.routeText("collectionKey"))
                    || !scope.allowsCollection(collection) || !scope.allowsDocument(head.routeText("documentId"))) continue;
            eligible.put(concept.id(), head);
        }
        return Map.copyOf(eligible);
    }

    private static int integer(JsonNode args, String name, int defaultValue) {
        if (!args.has(name)) return defaultValue;
        if (!args.get(name).isIntegralNumber() || !args.get(name).canConvertToInt()) throw new IllegalArgumentException(name + " must be an integer");
        return args.get(name).intValue();
    }
    private static double number(JsonNode args, String name, double defaultValue) {
        if (!args.has(name)) return defaultValue;
        if (!args.get(name).isNumber()) throw new IllegalArgumentException(name + " must be a number");
        return args.get(name).doubleValue();
    }
    private static boolean bool(JsonNode args, String name, boolean defaultValue) {
        if (!args.has(name)) return defaultValue;
        if (!args.get(name).isBoolean()) throw new IllegalArgumentException(name + " must be a boolean");
        return args.get(name).booleanValue();
    }
}
