package com.harness.agent.knowledge;

import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.graph.GraphSpaceReference;
import com.harness.core.knowledge.KnowledgeSearchOptions;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeHandle;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.PageResponse;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.index.KnowledgeProjection;
import com.harness.tool.knowledge.index.KnowledgeProjectionHit;
import com.harness.tool.knowledge.index.KnowledgeProjectionSearch;
import com.harness.tool.knowledge.index.KnowledgeProjectionSearchOutcome;
import com.harness.tool.knowledge.index.KnowledgeProjectionStore;
import com.harness.tool.knowledge.index.KnowledgeRetrievalDiagnostics;
import com.harness.tool.rag.RagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    private static final int STALE_OVERFETCH = 20;
    private static final int MAX_RECENT_SCAN = 200;

    private static final Set<KnowledgeConceptType> SEARCHABLE_TYPES = Set.of(
            KnowledgeConceptType.SOURCE_DOCUMENT,
            KnowledgeConceptType.GRAPH_SCHEMA,
            KnowledgeConceptType.USER_EPISODE,
            KnowledgeConceptType.OPERATION_PLAYBOOK);

    private final KnowledgeRepository repository;
    private final KnowledgeProjectionStore projectionStore;
    private final EmbeddingModelProvider embeddingProvider;
    private final KnowledgeAccessService documentExecutor;
    private final KnowledgeGraphTool graphExecutor;
    private final Clock clock;

    public KnowledgeDiscoveryRouter(
            KnowledgeRepository repository,
            KnowledgeProjectionStore projectionStore,
            EmbeddingModelProvider embeddingProvider,
            KnowledgeAccessService documentExecutor,
            KnowledgeGraphTool graphExecutor,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore");
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.documentExecutor = Objects.requireNonNull(documentExecutor, "documentExecutor");
        this.graphExecutor = graphExecutor;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<DiscoveredKnowledge> search(String query, Set<KnowledgeConceptType> requestedTypes,
            int limit, KnowledgeToolRuntimeContext context) {
        return search(query, requestedTypes, KnowledgeSearchOptions.defaults(limit), context);
    }

    public boolean rerankAvailable() { return documentExecutor.rerankAvailable(); }

    public List<DiscoveredKnowledge> search(String query, Set<KnowledgeConceptType> requestedTypes,
            KnowledgeSearchOptions options, KnowledgeToolRuntimeContext context) {
        Objects.requireNonNull(options, "options");
        String normalizedQuery = requireQuery(query);
        Objects.requireNonNull(context, "context");
        Set<KnowledgeConceptType> types = searchableTypes(requestedTypes);
        if (types.isEmpty()) {
            // Reachable from the model (a knowledgeTypes value outside the searchable set), and
            // it must still overwrite the previous search's counters rather than leave them
            // describing a search that never ran.
            recordSearchDiagnostics(
                    context, "hybrid", normalizedQuery, types, null, 0, 0);
            return List.of();
        }
        float[] embedding = options.bm25Weight() == 1 ? new float[0] : requireEmbedding(normalizedQuery);
        KnowledgeProjectionSearchOutcome outcome = projectionStore.searchHybrid(
                new KnowledgeProjectionSearch(
                        normalizedQuery, embedding, context.tenantId(), context.userId(), null, null, false, types,
                        options.candidateTopK(), options.candidateTopK(),
                        options.denseThreshold(), options.sparseThreshold(), KnowledgeSearchOptions.RRF_K,
                        options.bm25Weight()));
        List<KnowledgeProjectionHit> hits = outcome.hits();
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
        List<DiscoveredKnowledge> results =
                routeWiki(normalizedQuery, current, heads, context, options);
        recordSearchDiagnostics(context, "hybrid", normalizedQuery, types,
                outcome.diagnostics(), current.size(), results.size());
        return results;
    }

    /**
     * Answer a history question that has no searchable subject ("what did I ask before?").
     *
     * <p>Such a question cannot score well against any single episode, so hybrid search is the
     * wrong instrument: this lists the most recent live USER_EPISODE revisions for the caller
     * instead. It stays inside the Wiki lifecycle — the rows, scope checks and staleness rule
     * are the same ones {@code knowledge_search} already applies.</p>
     */
    public List<DiscoveredKnowledge> recentEpisodes(
            int limit,
            KnowledgeToolRuntimeContext context
    ) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        Objects.requireNonNull(context, "context");
        if (context.userId() == null) {
            throw new IllegalArgumentException(
                    "Recent memory recall requires a userId; "
                            + "an anonymous caller has no episode scope");
        }
        // Staleness is only known after the rows are read, so a page of expired episodes would
        // shrink the answer below `limit` — or empty it — while live ones sit further down.
        // ponytail: fixed over-fetch, page with KnowledgeConceptCursor if expiring episodes
        // ever become common enough to exhaust it.
        int overFetch = Math.min(limit + STALE_OVERFETCH, MAX_RECENT_SCAN);
        PageResponse<KnowledgeConcept> page = repository.findPage(
                context.tenantId(), context.userId(),
                KnowledgeNamespaceType.USER_MEMORY, KnowledgeConceptType.USER_EPISODE,
                KnowledgeStatus.STABLE, null, overFetch);
        List<DiscoveredKnowledge> results = new ArrayList<>();
        int rank = 1;
        for (KnowledgeConcept concept : page.items()) {
            if (results.size() >= limit) break;
            // findPage(status=stable) does not apply stale_after; the scan-based callers filter
            // it explicitly and so must this path, or expired episodes resurface here.
            if (concept.isStaleAt(clock.instant())) continue;
            KnowledgeHead head = repository.findAuthorityById(concept.id()).orElse(null);
            if (head == null || head.currentRevision() == null) {
                continue;
            }
            KnowledgeRevision revision =
                    repository.findMetadataSnapshot(head.currentVersion()).revision();
            results.add(new DiscoveredKnowledge(
                    KnowledgeConceptType.USER_EPISODE,
                    concept.id(),
                    head.currentVersion(),
                    head.routeType(),
                    KnowledgeHandle.concept(
                            KnowledgeConceptType.USER_EPISODE,
                            concept.id(),
                            head.currentVersion(),
                            head.routeType()),
                    revision.title(),
                    summarize(revision.description()),
                    "recencyRank",
                    rank++,
                    List.of(memoryAnchors(
                            concept.id(), head.currentVersion(), concept.eventTime())),
                    Map.of(),
                    concept.eventTime()));
        }
        // Written with the same key set as the hybrid path and null diagnostics, so a trace never
        // mixes lane evidence from an earlier search with a recall that never touched Milvus.
        recordSearchDiagnostics(
                context, "recent", "", Set.of(KnowledgeConceptType.USER_EPISODE), null, 0,
                results.size());
        return List.copyOf(results);
    }

    /**
     * Records one search's evidence, so an empty result can be told apart from a result that was
     * cut by a threshold.
     *
     * <p>Every key is written on every path even when the value does not apply, because the trace
     * merges metadata: leaving a key out would silently attribute the previous search's numbers
     * to this one. Keys are otherwise last-write-wins — a run that searches several times keeps
     * the evidence of its most recent search only.</p>
     *
     * @param diagnostics the lane evidence, or null for a path that runs no vector lanes
     */
    private static void recordSearchDiagnostics(
            KnowledgeToolRuntimeContext context,
            String mode,
            String query,
            Set<KnowledgeConceptType> types,
            KnowledgeRetrievalDiagnostics diagnostics,
            int authorizedHits,
            int finalHits
    ) {
        if (context.runTrace() == null) {
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("knowledge_search_mode", mode);
        metadata.put("knowledge_search_query", truncate(query, 200));
        metadata.put("knowledge_search_types", types.stream()
                .sorted(Comparator.comparing(KnowledgeConceptType::name))
                .map(KnowledgeConceptType::name)
                .collect(java.util.stream.Collectors.joining(",")));
        metadata.put("knowledge_search_collections",
                diagnostics == null ? "" : String.join(",", diagnostics.collections()));
        metadata.put("knowledge_search_dense_candidates",
                countText(diagnostics, knowledge -> knowledge.denseCandidates()));
        metadata.put("knowledge_search_dense_best_score",
                diagnostics == null ? "" : scoreText(diagnostics.denseBestScore()));
        metadata.put("knowledge_search_dense_threshold",
                thresholdText(diagnostics, knowledge -> knowledge.denseThreshold()));
        metadata.put("knowledge_search_dense_kept",
                countText(diagnostics, knowledge -> knowledge.denseKept()));
        metadata.put("knowledge_search_sparse_candidates",
                countText(diagnostics, knowledge -> knowledge.sparseCandidates()));
        metadata.put("knowledge_search_sparse_best_score",
                diagnostics == null ? "" : scoreText(diagnostics.sparseBestScore()));
        metadata.put("knowledge_search_sparse_threshold",
                thresholdText(diagnostics, knowledge -> knowledge.sparseThreshold()));
        metadata.put("knowledge_search_sparse_kept",
                countText(diagnostics, knowledge -> knowledge.sparseKept()));
        metadata.put("knowledge_search_fused_candidates",
                countText(diagnostics, knowledge -> knowledge.fusedCandidates()));
        metadata.put("knowledge_search_authorized_hits", String.valueOf(authorizedHits));
        metadata.put("knowledge_search_final_hits", String.valueOf(finalHits));
        context.runTrace().putMetadata(metadata);
    }

    private static String countText(
            KnowledgeRetrievalDiagnostics diagnostics,
            java.util.function.ToIntFunction<KnowledgeRetrievalDiagnostics> extractor) {
        return diagnostics == null ? "" : String.valueOf(extractor.applyAsInt(diagnostics));
    }

    private static String thresholdText(
            KnowledgeRetrievalDiagnostics diagnostics,
            java.util.function.ToDoubleFunction<KnowledgeRetrievalDiagnostics> extractor) {
        return diagnostics == null ? "" : String.valueOf(extractor.applyAsDouble(diagnostics));
    }

    private static String scoreText(Double score) {
        return score == null ? "" : String.format(java.util.Locale.ROOT, "%.4f", score);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
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
        // A Schema card is published before its Schema is enabled, so that creating one shows what it
        // can answer; it must not reach the model as an available capability until it is enabled.
        if ((concept.conceptType() == KnowledgeConceptType.GRAPH_SCHEMA
                || concept.conceptType() == KnowledgeConceptType.GRAPH_SPACE)
                && Boolean.FALSE.equals(head.currentRevision().metadata().get("enabled"))) {
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
            KnowledgeSearchOptions options
    ) {
        List<KnowledgeProjectionHit> documents = hits.stream()
                .filter(hit -> hit.projection().conceptType()
                        == KnowledgeConceptType.SOURCE_DOCUMENT)
                .toList();
        Map<String, DiscoveredKnowledge> documentResults =
                routeDocuments(query, documents, heads, context, options);
        List<DiscoveredKnowledge> results = new ArrayList<>();
        for (KnowledgeProjectionHit hit : hits) {
            if (results.size() >= options.limit()) break;
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
            KnowledgeSearchOptions options
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
            Map<String, String> documentRevisions = new LinkedHashMap<>();
            entry.getValue().forEach(id -> documentRevisions.put(id, heads.get(id).concept().currentRevisionId()));
            var documentResult = documentExecutor.searchDocumentRevisions(
                    query, entry.getKey(), options, documentRevisions);
            for (RagRetriever.RagDocument document : documentResult.documents()) {
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
                        summarize(document.content()), documentResult.metadata().get("scoreType"), document.score(),
                        List.of(Map.of(
                                "documentId", documentId,
                                "revisionId", revisionId,
                                "chunkIndex", document.chunkIndex())), Map.of(), null));
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
                Map.of(),
                null);
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
                scoreType(projection.conceptType()), hit.rrfScore(), List.of(), graphRouteHint,
                projection.eventTime());
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
                List.of(memoryAnchors(projection)),
                Map.of(),
                projection.eventTime());
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

    /**
     * Source anchors for a memory hit, carrying the event time when there is one so the model can
     * say when something happened rather than only that it happened.
     */
    private static Map<String, Object> memoryAnchors(KnowledgeProjection projection) {
        return memoryAnchors(
                projection.conceptId(), projection.revisionId(), projection.eventTime());
    }

    private static Map<String, Object> memoryAnchors(
            String conceptId, String revisionId, java.time.Instant eventTime) {
        Map<String, Object> anchors = new LinkedHashMap<>();
        anchors.put("memoryId", conceptId);
        anchors.put("revisionId", revisionId);
        if (eventTime != null) {
            anchors.put("eventTime", eventTime.toString());
        }
        return Map.copyOf(anchors);
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

}
