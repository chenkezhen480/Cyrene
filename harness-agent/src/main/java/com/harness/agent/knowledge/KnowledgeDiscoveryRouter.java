package com.harness.agent.knowledge;

import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.graph.GraphSpaceReference;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeHandle;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.index.KnowledgeProjection;
import com.harness.tool.knowledge.index.KnowledgeProjectionHit;
import com.harness.tool.knowledge.index.KnowledgeProjectionSearch;
import com.harness.tool.knowledge.index.KnowledgeProjectionStore;
import com.harness.tool.rag.RagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Discovers authorized knowledge across Catalog, User Episode, and Playbook indexes. */
public final class KnowledgeDiscoveryRouter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDiscoveryRouter.class);

    public static final int LANE_TOP_K = 20;
    public static final int FUSED_TOP_K = 20;
    public static final double DENSE_THRESHOLD = 0.70;
    public static final double SPARSE_THRESHOLD = 0.10;
    public static final int RRF_K = 60;

    private static final Set<KnowledgeConceptType> SEARCHABLE_TYPES = Set.of(
            KnowledgeConceptType.SOURCE_DOCUMENT,
            KnowledgeConceptType.GRAPH_SCHEMA,
            KnowledgeConceptType.GRAPH_SPACE,
            KnowledgeConceptType.USER_EPISODE,
            KnowledgeConceptType.OPERATION_PLAYBOOK);

    private final KnowledgeRepository repository;
    private final KnowledgeProjectionStore projectionStore;
    private final EmbeddingModelProvider embeddingProvider;
    private final KnowledgeAccessService documentExecutor;
    private final KnowledgeGraphTool graphExecutor;
    private final Clock clock;
    private final Settings settings;

    public KnowledgeDiscoveryRouter(
            KnowledgeRepository repository,
            KnowledgeProjectionStore projectionStore,
            EmbeddingModelProvider embeddingProvider,
            KnowledgeAccessService documentExecutor,
            KnowledgeGraphTool graphExecutor,
            Clock clock
    ) {
        this(repository, projectionStore, embeddingProvider,
                documentExecutor, graphExecutor, clock, Settings.fromEnvironment());
    }

    KnowledgeDiscoveryRouter(
            KnowledgeRepository repository,
            KnowledgeProjectionStore projectionStore,
            EmbeddingModelProvider embeddingProvider,
            KnowledgeAccessService documentExecutor,
            KnowledgeGraphTool graphExecutor,
            Clock clock,
            Settings settings
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore");
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.documentExecutor = Objects.requireNonNull(documentExecutor, "documentExecutor");
        this.graphExecutor = graphExecutor;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public List<DiscoveredKnowledge> search(
            String query,
            Set<KnowledgeConceptType> requestedTypes,
            int limit,
            KnowledgeToolRuntimeContext context
    ) {
        String normalizedQuery = requireQuery(query);
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        Objects.requireNonNull(context, "context");
        Set<KnowledgeConceptType> types = searchableTypes(requestedTypes);
        if (types.isEmpty()) return List.of();
        float[] embedding = requireEmbedding(normalizedQuery);
        List<KnowledgeProjectionHit> hits = projectionStore.searchHybrid(
                new KnowledgeProjectionSearch(
                        normalizedQuery, embedding, context.tenantId(), context.userId(), types,
                        settings.laneTopK(), settings.fusedTopK(),
                        settings.denseThreshold(), settings.sparseThreshold(), settings.rrfK()));
        Map<String, KnowledgeHead> heads = new LinkedHashMap<>(repository.findAuthorityByIds(hits.stream()
                .map(hit -> hit.projection().conceptId()).distinct().toList()));
        for (var hit : hits) {
            var projection = hit.projection();
            var head = heads.get(projection.conceptId());
            if (head == null || head.currentRevision() == null
                    || !projection.revisionId().equals(head.concept().currentRevisionId())) continue;
            var r = head.currentRevision();
            heads.put(projection.conceptId(), head.withRevision(
                    new com.harness.core.knowledge.KnowledgeRevision(r.id(), r.conceptId(), r.revisionNumber(),
                            projection.title(), projection.description(), "", r.generatedBy(), r.generatedAt(),
                            r.contentHash(), r.metadata(), r.createdAt())));
        }
        Set<String> readableGraphSchemas = readableGraphSchemas(heads, context);
        Set<String> readableGraphSpaces = readableGraphSpaces(hits, heads, context);
        List<KnowledgeProjectionHit> current = hits.stream()
                .filter(hit -> currentAndReadable(
                        hit.projection(), heads, context,
                        readableGraphSchemas, readableGraphSpaces))
                .toList();
        return routeWiki(normalizedQuery, current, heads, context, limit);
    }

    private boolean currentAndReadable(
            KnowledgeProjection projection,
            Map<String, KnowledgeHead> heads,
            KnowledgeToolRuntimeContext context,
            Set<String> readableGraphSchemas,
            Set<String> readableGraphSpaces
    ) {
        KnowledgeHead head = heads.get(projection.conceptId());
        if (head == null || head.currentRevision() == null) return false;
        KnowledgeConcept concept = head.concept();
        if (concept.status() != KnowledgeStatus.STABLE
                || concept.isStaleAt(clock.instant())
                || !projection.revisionId().equals(head.currentVersion())
                || projection.conceptType() != concept.conceptType()
                || !scopeMatches(concept, context)) {
            return false;
        }
        if (concept.conceptType() == KnowledgeConceptType.GRAPH_SCHEMA
                && !readableGraphSchemas.contains(head.routeText("schemaId"))) {
            return false;
        }
        if (concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE
                && !readableGraphSpaces.contains(concept.id())) {
            return false;
        }
        if ((concept.conceptType() == KnowledgeConceptType.GRAPH_SCHEMA
                || concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE)
                && context.graphRequestContext() != null
                && !Objects.equals(
                graphSchemaId(head), context.graphRequestContext().schemaId())) {
            return false;
        }
        if (concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE
                && context.graphRequestContext() != null
                && !Objects.equals(head.routeText("graphId"),
                        context.graphRequestContext().graphId())) {
            return false;
        }
        return concept.conceptType() != KnowledgeConceptType.SOURCE_DOCUMENT
                || context.allowsCollection(head.routeText("collectionKey"))
                && context.allowsDocument(head.routeText("documentId"));
    }

    private Set<String> readableGraphSchemas(
            Map<String, KnowledgeHead> heads,
            KnowledgeToolRuntimeContext context
    ) {
        Set<String> schemaIds = heads.values().stream()
                .filter(head -> head.concept().conceptType() == KnowledgeConceptType.GRAPH_SCHEMA)
                .map(head -> head.routeText("schemaId"))
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (schemaIds.isEmpty()) return Set.of();
        if (graphExecutor == null || !context.authorizedTools().contains(KnowledgeGraphTool.TOOL_NAME)) return Set.of();
        return graphExecutor.readableWikiSchemas(context.tenantId(), schemaIds);
    }

    private Set<String> readableGraphSpaces(
            List<KnowledgeProjectionHit> hits,
            Map<String, KnowledgeHead> heads,
            KnowledgeToolRuntimeContext context
    ) {
        Map<String, GraphSpaceReference> candidates = new LinkedHashMap<>();
        for (KnowledgeProjectionHit hit : hits) {
            KnowledgeProjection projection = hit.projection();
            if (projection.conceptType() != KnowledgeConceptType.GRAPH_SPACE) continue;
            KnowledgeHead head = heads.get(projection.conceptId());
            if (head == null || head.currentRevision() == null) continue;
            String graphId = head.routeText("graphId");
            String schemaId = head.routeText("schemaId");
            if (graphId != null && schemaId != null) {
                candidates.put(projection.conceptId(),
                        new GraphSpaceReference(graphId, schemaId));
            }
        }
        if (candidates.isEmpty()) return Set.of();
        if (graphExecutor == null || !context.authorizedTools().contains(KnowledgeGraphTool.TOOL_NAME)) return Set.of();
        return graphExecutor.readableWikiGraphSpaces(context.tenantId(), candidates);
    }

    private List<DiscoveredKnowledge> routeWiki(
            String query,
            List<KnowledgeProjectionHit> hits,
            Map<String, KnowledgeHead> heads,
            KnowledgeToolRuntimeContext context,
            int limit
    ) {
        List<KnowledgeProjectionHit> documents = hits.stream()
                .filter(hit -> hit.projection().conceptType()
                        == KnowledgeConceptType.SOURCE_DOCUMENT)
                .toList();
        Map<String, DiscoveredKnowledge> documentResults =
                routeDocuments(query, documents, heads, context, limit);
        List<DiscoveredKnowledge> results = new ArrayList<>();
        for (KnowledgeProjectionHit hit : hits) {
            if (results.size() >= limit) break;
            if (hit.projection().conceptType() == KnowledgeConceptType.SOURCE_DOCUMENT) {
                results.add(documentResults.getOrDefault(
                        hit.projection().conceptId(), documentConceptResult(
                                hit, heads.get(hit.projection().conceptId()))));
            } else if (hit.projection().conceptType() == KnowledgeConceptType.USER_EPISODE
                    || hit.projection().conceptType() == KnowledgeConceptType.OPERATION_PLAYBOOK) {
                results.add(memoryResult(hit, heads.get(hit.projection().conceptId())));
            } else {
                results.add(conceptResult(hit, heads.get(hit.projection().conceptId())));
            }
        }
        return List.copyOf(results);
    }

    private Map<String, DiscoveredKnowledge> routeDocuments(
            String query,
            List<KnowledgeProjectionHit> catalogHits,
            Map<String, KnowledgeHead> heads,
            KnowledgeToolRuntimeContext context,
            int limit
    ) {
        Map<String, Set<String>> documentsByCollection = new LinkedHashMap<>();
        for (KnowledgeProjectionHit hit : catalogHits) {
            KnowledgeHead head = heads.get(hit.projection().conceptId());
            if (head == null) continue;
            KnowledgeConcept concept = head.concept();
            documentsByCollection.computeIfAbsent(
                    head.routeText("collectionKey"), ignored -> new LinkedHashSet<>()).add(head.routeText("documentId"));
        }
        Map<String, DiscoveredKnowledge> results = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : documentsByCollection.entrySet()) {
            int searchLimit = Math.min(documentExecutor.maxSearchLimit(),
                    Math.max(limit, entry.getValue().size()));
            Map<String, String> documentRevisions = new LinkedHashMap<>();
            entry.getValue().forEach(id -> documentRevisions.put(id, heads.get(id).concept().currentRevisionId()));
            for (RagRetriever.RagDocument document : documentExecutor.searchDocumentRevisions(
                    query, entry.getKey(), searchLimit, documentRevisions).documents()) {
                String documentId = metadataText(document.metadata(), "document_id");
                String revisionId = metadataText(document.metadata(), "revision_id");
                KnowledgeHead head = heads.get(documentId);
                if (documentId == null || revisionId == null || head == null
                        || !entry.getValue().contains(documentId)
                        || !revisionId.equals(head.concept().currentRevisionId())
                        || !context.allowsDocument(documentId)) {
                    continue;
                }
                KnowledgeHandle handle = KnowledgeHandle.document(
                        KnowledgeConceptType.SOURCE_DOCUMENT, documentId, revisionId,
                        entry.getKey(), documentId, document.chunkIndex());
                results.putIfAbsent(documentId, new DiscoveredKnowledge(
                        KnowledgeConceptType.SOURCE_DOCUMENT, documentId, revisionId,
                        KnowledgeRouteTarget.DOCUMENT, handle, head.currentRevision().title(),
                        summarize(document.content()), "documentRerankScore", document.score(),
                        List.of(Map.of(
                                "documentId", documentId,
                                "revisionId", revisionId,
                                "chunkIndex", document.chunkIndex())), Map.of()));
            }
        }
        return Map.copyOf(results);
    }

    private static DiscoveredKnowledge documentConceptResult(
            KnowledgeProjectionHit hit,
            KnowledgeHead head
    ) {
        KnowledgeProjection projection = hit.projection();
        return new DiscoveredKnowledge(
                KnowledgeConceptType.SOURCE_DOCUMENT,
                projection.conceptId(),
                projection.revisionId(),
                KnowledgeRouteTarget.DOCUMENT,
                KnowledgeHandle.document(
                        KnowledgeConceptType.SOURCE_DOCUMENT,
                        projection.conceptId(),
                        projection.revisionId(),
                        head.routeText("collectionKey"),
                        head.routeText("documentId"),
                        0),
                head.currentRevision().title(),
                summarize(projection.description() == null
                        ? projection.content() : projection.description()),
                "wikiRrfScore",
                hit.rrfScore(),
                List.of(Map.of(
                        "documentId", projection.conceptId(),
                        "revisionId", projection.revisionId())),
                Map.of());
    }

    private DiscoveredKnowledge conceptResult(
            KnowledgeProjectionHit hit,
            KnowledgeHead head
    ) {
        KnowledgeProjection projection = hit.projection();
        Map<String, Object> graphRouteHint = graphRouteHint(projection, head);
        return new DiscoveredKnowledge(
                projection.conceptType(), projection.conceptId(), projection.revisionId(),
                head.routeType(),
                KnowledgeHandle.concept(
                        projection.conceptType(), projection.conceptId(),
                        projection.revisionId(), head.routeType()),
                head.currentRevision().title(),
                summarize(projection.description() == null
                        ? projection.content() : projection.description()),
                scoreType(projection.conceptType()), hit.rrfScore(), List.of(), graphRouteHint);
    }

    /**
     * Memory hits follow the Wiki chain: the catalog summary provides the semantic entry,
     * MySQL authority is already validated, and only then is the body block resolved from
     * the dedicated memory collection. The handle still routes to knowledge_read for the
     * full block; sourceAnchors point at that memory block.
     */
    private DiscoveredKnowledge memoryResult(
            KnowledgeProjectionHit hit,
            KnowledgeHead head
    ) {
        KnowledgeProjection projection = hit.projection();
        return new DiscoveredKnowledge(
                projection.conceptType(), projection.conceptId(), projection.revisionId(),
                head.routeType(),
                KnowledgeHandle.concept(
                        projection.conceptType(), projection.conceptId(),
                        projection.revisionId(), head.routeType()),
                head.currentRevision().title(),
                summarize(memoryBody(projection)),
                scoreType(projection.conceptType()), hit.rrfScore(),
                List.of(Map.of(
                        "memoryId", projection.conceptId(),
                        "revisionId", projection.revisionId())),
                Map.of());
    }

    /** Hydrates the validated hit from the dedicated memory collection, falling back to the catalog summary. */
    private String memoryBody(KnowledgeProjection projection) {
        String fallback = projection.description() == null
                ? projection.content() : projection.description();
        try {
            return projectionStore.findMemory(
                            projection.conceptType(), projection.conceptId(), projection.revisionId())
                    .map(KnowledgeProjection::content)
                    .filter(value -> value != null && !value.isBlank())
                    .orElse(fallback);
        } catch (RuntimeException e) {
            log.warn("[KnowledgeDiscovery] Memory block read failed for {}; using catalog summary",
                    projection.conceptId(), e);
            return fallback;
        }
    }

    private Map<String, Object> graphRouteHint(
            KnowledgeProjection projection,
            KnowledgeHead head
    ) {
        if (head.routeType() != KnowledgeRouteTarget.GRAPH) return Map.of();
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("recommendedTool", KnowledgeGraphTool.TOOL_NAME);
        projectionStore.findRevisionSnapshot(head.currentVersion()).ifPresent(snapshot -> {
            if (snapshot.conceptType() != projection.conceptType()
                    || !snapshot.revision().conceptId().equals(head.concept().id())
                    || !snapshot.revision().id().equals(head.currentVersion())) {
                throw new SecurityException("Graph capability card does not match the authorized Wiki revision");
            }
            for (String field : List.of("entityTypes", "relationTypes", "typicalQueries")) {
                Object value = snapshot.revision().metadata().get(field);
                if (value != null) hint.put(field, value);
            }
        });
        if (projection.conceptType() == KnowledgeConceptType.GRAPH_SPACE) {
            String graphId = head.routeText("graphId");
            String schemaId = head.routeText("schemaId");
            if (graphId != null) hint.put("graphId", graphId);
            if (schemaId != null) hint.put("schemaId", schemaId);
        } else {
            hint.put("schemaId", head.routeText("schemaId"));
        }
        return Map.copyOf(hint);
    }

    private static String graphSchemaId(KnowledgeHead head) {
        return head.routeText("schemaId");
    }

    private static String scoreType(KnowledgeConceptType conceptType) {
        return switch (conceptType) {
            case USER_EPISODE -> "wikiRrfScore";
            case OPERATION_PLAYBOOK -> "wikiRrfScore";
            case SOURCE_DOCUMENT, GRAPH_SCHEMA, GRAPH_SPACE -> "wikiRrfScore";
            case USER_PREFERENCE -> throw new IllegalArgumentException(
                    "User Preference is not searchable");
        };
    }

    private float[] requireEmbedding(String query) {
        if (!embeddingProvider.isAvailable()) {
            throw new IllegalStateException("Knowledge discovery embedding provider is unavailable");
        }
        var embedding = embeddingProvider.embed(query);
        if (embedding == null || embedding.vector() == null
                || embedding.vector().length != embeddingProvider.dimension()) {
            throw new IllegalStateException("Knowledge discovery embedding dimension mismatch");
        }
        return embedding.vector();
    }

    private static Set<KnowledgeConceptType> searchableTypes(
            Set<KnowledgeConceptType> requestedTypes
    ) {
        if (requestedTypes == null || requestedTypes.isEmpty()) return SEARCHABLE_TYPES;
        EnumSet<KnowledgeConceptType> result = EnumSet.noneOf(KnowledgeConceptType.class);
        requestedTypes.stream().filter(SEARCHABLE_TYPES::contains).forEach(result::add);
        return Set.copyOf(result);
    }

    private static boolean scopeMatches(
            KnowledgeConcept concept,
            KnowledgeToolRuntimeContext context
    ) {
        if (concept.conceptType() == KnowledgeConceptType.USER_EPISODE) {
            return context.userId() != null
                    && Objects.equals(concept.userId(), context.userId())
                    && Objects.equals(concept.tenantId(), context.tenantId());
        }
        return concept.userId() == null
                && (concept.tenantId() == null
                || Objects.equals(concept.tenantId(), context.tenantId()));
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank() || query.length() > 4096) {
            throw new IllegalArgumentException("query must contain 1 to 4096 characters");
        }
        return query.trim();
    }

    private static String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String summarize(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "…";
    }

    public record Settings(
            int laneTopK,
            int fusedTopK,
            double denseThreshold,
            double sparseThreshold,
            int rrfK
    ) {
        public Settings {
            if (laneTopK < 1 || laneTopK > 100
                    || fusedTopK < 1 || fusedTopK > laneTopK
                    || denseThreshold < -1 || denseThreshold > 1
                    || sparseThreshold < 0 || rrfK < 1 || rrfK > 1000) {
                throw new IllegalArgumentException("invalid Wiki retrieval settings");
            }
        }

        public static Settings fromEnvironment() {
            EnvConfig config = EnvConfig.get();
            return new Settings(
                    config.getInt(
                            EnvKey.KNOWLEDGE_CATALOG_RETRIEVAL_LANE_TOP_K, LANE_TOP_K),
                    config.getInt(
                            EnvKey.KNOWLEDGE_CATALOG_RETRIEVAL_FUSED_TOP_K, FUSED_TOP_K),
                    config.getDouble(
                            EnvKey.KNOWLEDGE_CATALOG_RETRIEVAL_DENSE_THRESHOLD,
                            DENSE_THRESHOLD),
                    config.getDouble(
                            EnvKey.KNOWLEDGE_CATALOG_RETRIEVAL_SPARSE_THRESHOLD,
                            SPARSE_THRESHOLD),
                    config.getInt(EnvKey.KNOWLEDGE_CATALOG_RETRIEVAL_RRF_K, RRF_K));
        }
    }
}
