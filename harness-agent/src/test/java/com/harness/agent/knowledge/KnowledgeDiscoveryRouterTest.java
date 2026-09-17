package com.harness.agent.knowledge;

import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.ContextBuilder;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.index.KnowledgeProjection;
import com.harness.tool.knowledge.index.KnowledgeProjectionHit;
import com.harness.tool.knowledge.index.KnowledgeProjectionSearchOutcome;
import com.harness.tool.knowledge.index.KnowledgeRetrievalDiagnostics;
import com.harness.tool.knowledge.index.KnowledgeProjectionStore;
import com.harness.tool.rag.RagRetriever;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDiscoveryRouterTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private KnowledgeRepository repository;
    private KnowledgeProjectionStore projectionStore;
    private EmbeddingModelProvider embeddingProvider;
    private KnowledgeAccessService documentExecutor;
    private KnowledgeGraphTool graphExecutor;
    private KnowledgeDiscoveryRouter router;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeRepository.class);
        projectionStore = mock(KnowledgeProjectionStore.class);
        embeddingProvider = mock(EmbeddingModelProvider.class);
        documentExecutor = mock(KnowledgeAccessService.class);
        graphExecutor = mock(KnowledgeGraphTool.class);
        when(embeddingProvider.isAvailable()).thenReturn(true);
        when(embeddingProvider.dimension()).thenReturn(3);
        when(embeddingProvider.embed(any(String.class)))
                .thenReturn(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}));
        router = new KnowledgeDiscoveryRouter(
                repository, projectionStore, embeddingProvider,
                documentExecutor, graphExecutor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new KnowledgeDiscoveryRouter.Settings(20, 20, 0.70, 0.10, 60));
    }

    @Test
    void defaultSearchSpansDedicatedIndexesAndDropsNonCurrentRevision() {
        KnowledgeProjection current = projection(
                "concept-current", "revision-current", KnowledgeConceptType.OPERATION_PLAYBOOK);
        KnowledgeProjection obsolete = projection(
                "concept-obsolete", "revision-obsolete", KnowledgeConceptType.OPERATION_PLAYBOOK);
        when(projectionStore.searchHybrid(any())).thenReturn(outcomeOf(
                new KnowledgeProjectionHit(current, 0.03),
                new KnowledgeProjectionHit(obsolete, 0.02)));
        when(repository.findAuthorityByIds(any())).thenReturn(Map.of(
                current.conceptId(), head(current, current.revisionId()),
                obsolete.conceptId(), head(obsolete, "revision-new")));
        when(projectionStore.findMemory(
                KnowledgeConceptType.OPERATION_PLAYBOOK, "concept-current", "revision-current"))
                .thenReturn(java.util.Optional.of(current));

        List<DiscoveredKnowledge> results = router.search(
                "topic", Set.of(), 10, context());

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.conceptId()).isEqualTo("concept-current");
            assertThat(result.routeTarget()).isEqualTo(KnowledgeRouteTarget.OPERATION_MEMORY);
            assertThat(result.summary()).isEqualTo(current.content());
            assertThat(result.sourceAnchors()).singleElement().satisfies(anchor -> {
                assertThat(anchor)
                        .containsEntry("memoryId", "concept-current")
                        .containsEntry("revisionId", "revision-current");
            });
        });
        verify(projectionStore).searchHybrid(any());
    }

    @Test
    void preferenceIsExcludedButEpisodesAndPlaybooksRemainSearchable() {
        KnowledgeProjection episode = projection(
                "episode", "episode-revision", KnowledgeConceptType.USER_EPISODE);
        KnowledgeProjection playbook = projection(
                "playbook", "playbook-revision", KnowledgeConceptType.OPERATION_PLAYBOOK);
        when(projectionStore.searchHybrid(any())).thenReturn(outcomeOf(
                new KnowledgeProjectionHit(episode, 0.03),
                new KnowledgeProjectionHit(playbook, 0.02)));
        when(repository.findAuthorityByIds(any())).thenReturn(Map.of(
                episode.conceptId(), head(episode, episode.revisionId()),
                playbook.conceptId(), head(playbook, playbook.revisionId())));
        when(projectionStore.findMemory(
                KnowledgeConceptType.USER_EPISODE, "episode", "episode-revision"))
                .thenReturn(java.util.Optional.of(episode));
        when(projectionStore.findMemory(
                KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook", "playbook-revision"))
                .thenReturn(java.util.Optional.of(playbook));

        List<DiscoveredKnowledge> results = router.search(
                "remember", Set.of(
                        KnowledgeConceptType.USER_PREFERENCE,
                        KnowledgeConceptType.USER_EPISODE,
                        KnowledgeConceptType.OPERATION_PLAYBOOK), 10, context());

        assertThat(results).extracting(DiscoveredKnowledge::conceptId)
                .containsExactly("episode", "playbook");
        verify(projectionStore).searchHybrid(any());
    }

    @Test
    void episodeFromAnotherTenantIsRejectedByMysqlAuthorityCheck() {
        KnowledgeProjection crossTenant = new KnowledgeProjection(
                "episode-revision", "episode", "episode-revision",
                null, "user-a", KnowledgeNamespaceType.USER_MEMORY, null,
                KnowledgeConceptType.USER_EPISODE, KnowledgeRouteTarget.USER_MEMORY,
                "cyrene://knowledge/episode", "episode", "description", "body",
                NOW, NOW, null, null, List.of(), null);
        when(projectionStore.searchHybrid(any())).thenReturn(outcomeOf(
                new KnowledgeProjectionHit(crossTenant, 0.03)));
        when(repository.findAuthorityByIds(any())).thenReturn(Map.of(
                crossTenant.conceptId(), head(crossTenant, crossTenant.revisionId())));

        assertThat(router.search(
                "remember", Set.of(KnowledgeConceptType.USER_EPISODE), 10, context()))
                .isEmpty();
    }

    @Test
    void graphSchemaIsDiscoverableWithoutScopeAndRestrictedByTrustedGraphScope() {
        KnowledgeProjection schema = projection(
                "schema-concept", "schema-revision", KnowledgeConceptType.GRAPH_SCHEMA);
        when(projectionStore.searchHybrid(any())).thenReturn(outcomeOf(
                new KnowledgeProjectionHit(schema, 0.03)));
        when(repository.findAuthorityByIds(any())).thenReturn(Map.of(
                schema.conceptId(), head(schema, schema.revisionId())));
        when(graphExecutor.readableWikiSchemas("tenant-a", Set.of("schema-a")))
                .thenReturn(Set.of("schema-a"));

        assertThat(router.search(
                "schema", Set.of(KnowledgeConceptType.GRAPH_SCHEMA), 10, context()))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.routeTarget()).isEqualTo(KnowledgeRouteTarget.GRAPH);
                    assertThat(result.graphRouteHint()).containsEntry("schemaId", "schema-a")
                            .containsEntry("recommendedTool", "query_graph");
                });

        KnowledgeToolRuntimeContext mismatched = new KnowledgeToolRuntimeContext(
                "tenant-a", "user-a", null,
                new GraphRequestContext("graph-a", "schema-other", Set.of(), Set.of()),
                Set.of("knowledge_search", "knowledge_read", "query_graph"), null);
        assertThat(router.search(
                "schema", Set.of(KnowledgeConceptType.GRAPH_SCHEMA), 10, mismatched))
                .isEmpty();

        KnowledgeToolRuntimeContext scoped = new KnowledgeToolRuntimeContext(
                "tenant-a", "user-a", null,
                new GraphRequestContext("graph-a", "schema-a", Set.of(), Set.of()),
                Set.of("knowledge_search", "knowledge_read", "query_graph"), null);
        assertThat(router.search(
                "schema", Set.of(KnowledgeConceptType.GRAPH_SCHEMA), 10, scoped))
                .singleElement()
                .extracting(DiscoveredKnowledge::conceptId)
                .isEqualTo("schema-concept");
        org.mockito.Mockito.clearInvocations(graphExecutor);
        var detached = new KnowledgeToolRuntimeContext("tenant-a", "user-a", null, null,
                Set.of("knowledge_search", "knowledge_read"), null);
        assertThat(router.search("schema", Set.of(KnowledgeConceptType.GRAPH_SCHEMA), 10, detached)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(graphExecutor);
    }

    /**
     * A Schema card is published before its Schema is enabled so the console can show what it would
     * answer; the model must not be offered that capability until the Schema is enabled.
     */
    @Test
    void disabledGraphSchemaCardIsNotOfferedToTheModel() {
        KnowledgeProjection schema = projection(
                "schema-concept", "schema-revision", KnowledgeConceptType.GRAPH_SCHEMA);
        when(projectionStore.searchHybrid(any())).thenReturn(outcomeOf(
                new KnowledgeProjectionHit(schema, 0.03)));
        when(repository.findAuthorityByIds(any())).thenReturn(Map.of(
                schema.conceptId(),
                head(schema, schema.revisionId(), Map.of("enabled", false))));
        when(graphExecutor.readableWikiSchemas(any(), any())).thenReturn(Set.of("schema-a"));

        assertThat(router.search(
                "schema", Set.of(KnowledgeConceptType.GRAPH_SCHEMA), 10, context()))
                .isEmpty();
    }

    @Test
    void graphSpaceProjectionIsNotSearchableWikiContent() {
        KnowledgeProjection graphSpace = projection(
                "space-concept", "space-revision", KnowledgeConceptType.GRAPH_SPACE);
        when(projectionStore.searchHybrid(any())).thenReturn(outcomeOf(
                new KnowledgeProjectionHit(graphSpace, 0.03)));
        when(repository.findAuthorityByIds(any())).thenReturn(Map.of(
                graphSpace.conceptId(), head(graphSpace, graphSpace.revisionId())));
        assertThat(router.search(
                "student graph", Set.of(KnowledgeConceptType.GRAPH_SPACE), 10, context()))
                .isEmpty();
        org.mockito.Mockito.verifyNoInteractions(graphExecutor);
    }

    @Test
    void documentRoutingPreservesUnifiedWikiRankAndFallsBackToWikiHandle() {
        KnowledgeProjection playbook = projection(
                "playbook", "playbook-revision", KnowledgeConceptType.OPERATION_PLAYBOOK);
        KnowledgeProjection document = projection(
                "document", "document-revision", KnowledgeConceptType.SOURCE_DOCUMENT);
        when(projectionStore.searchHybrid(any())).thenReturn(outcomeOf(
                new KnowledgeProjectionHit(playbook, 0.04),
                new KnowledgeProjectionHit(document, 0.03)));
        when(repository.findAuthorityByIds(any())).thenReturn(Map.of(
                playbook.conceptId(), head(playbook, playbook.revisionId()),
                document.conceptId(), head(document, document.revisionId())));
        when(projectionStore.findMemory(
                KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook", "playbook-revision"))
                .thenReturn(java.util.Optional.of(playbook));
        when(documentExecutor.maxSearchLimit()).thenReturn(20);
        when(documentExecutor.searchDocumentRevisions("query", "collection-a", 10, Map.of("document", "document-revision")))
                .thenReturn(new ContextBuilder.ContextResult(List.of(
                        new RagRetriever.RagDocument(
                                "chunk-2", "matching source text", "source.md", 0.91,
                                Map.of(
                                        "document_id", "document",
                                        "revision_id", "document-revision"),
                                2)), Map.of()));

        List<DiscoveredKnowledge> routed = router.search(
                "query", Set.of(), 10, context());

        assertThat(routed).extracting(DiscoveredKnowledge::conceptId)
                .containsExactly("playbook", "document");
        assertThat(routed.get(1).handle().chunkIndex()).isEqualTo(2);

        when(documentExecutor.searchDocumentRevisions("query", "collection-a", 10, Map.of("document", "document-revision")))
                .thenReturn(new ContextBuilder.ContextResult(List.of(), Map.of()));
        List<DiscoveredKnowledge> fallback = router.search(
                "query", Set.of(), 10, context());
        assertThat(fallback.get(1).handle().chunkIndex()).isZero();
        assertThat(fallback.get(1).scoreType()).isEqualTo("wikiRrfScore");
    }

    private static KnowledgeToolRuntimeContext context() {
        return new KnowledgeToolRuntimeContext(
                "tenant-a", "user-a", null, null,
                Set.of("knowledge_search", "knowledge_read", "query_graph"), null);
    }

@Test
    void recordsHowFarEachRetrievalLaneGotBeforeItsThreshold() {
        KnowledgeProjection playbook = projection(
                "playbook", "playbook-revision", KnowledgeConceptType.OPERATION_PLAYBOOK);
        when(projectionStore.searchHybrid(any())).thenReturn(
                new KnowledgeProjectionSearchOutcome(
                        List.of(new KnowledgeProjectionHit(playbook, 0.03)),
                        new KnowledgeRetrievalDiagnostics(
                                List.of("cyrene_operation_knowledge"),
                                20, 0.64, 0.70, 0,
                                4, 0.21, 0.10, 2,
                                1)));
        when(repository.findAuthorityByIds(any())).thenReturn(
                Map.of(playbook.conceptId(), head(playbook, playbook.revisionId())));
        when(projectionStore.findMemory(
                KnowledgeConceptType.OPERATION_PLAYBOOK, "playbook", "playbook-revision"))
                .thenReturn(java.util.Optional.of(playbook));
        RunTrace trace = mock(RunTrace.class);

        router.search("topic", Set.of(), 10, contextWith(trace));

        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        verify(trace).putMetadata(captured.capture());
        assertThat(captured.getValue())
                .containsEntry("knowledge_search_query", "topic")
                .containsEntry("knowledge_search_collections", "cyrene_operation_knowledge")
                .containsEntry("knowledge_search_dense_candidates", "20")
                .containsEntry("knowledge_search_dense_best_score", "0.6400")
                .containsEntry("knowledge_search_dense_threshold", "0.7")
                .containsEntry("knowledge_search_dense_kept", "0")
                .containsEntry("knowledge_search_sparse_kept", "2")
                .containsEntry("knowledge_search_fused_candidates", "1")
                .containsEntry("knowledge_search_authorized_hits", "1")
                .containsEntry("knowledge_search_final_hits", "1");
    }

    @Test
    void recentRecallListsLiveEpisodesInOrderAndSkipsExpiredOnes() {
        KnowledgeConcept newest = episode("episode-new", "rev-new", null);
        KnowledgeConcept expired = episode("episode-old", "rev-old", NOW.minusSeconds(60));
        // The router over-fetches so expired rows cannot shrink the answer below the limit.
        when(repository.findPage(
                "tenant-a", "user-a", KnowledgeNamespaceType.USER_MEMORY,
                KnowledgeConceptType.USER_EPISODE, KnowledgeStatus.STABLE, null, 25))
                .thenReturn(new PageResponse<>(
                        List.of(newest, expired), new PageInfo(25, "", false)));
        for (KnowledgeConcept concept : List.of(newest, expired)) {
            when(repository.findAuthorityById(concept.id()))
                    .thenReturn(java.util.Optional.of(head(
                            projection(concept.id(), concept.currentRevisionId(),
                                    KnowledgeConceptType.USER_EPISODE),
                            concept.currentRevisionId())));
            when(repository.findMetadataSnapshot(concept.currentRevisionId()))
                    .thenReturn(new KnowledgeRevisionSnapshot(
                            KnowledgeConceptType.USER_EPISODE, null,
                            new KnowledgeRevision(
                                    concept.currentRevisionId(), concept.id(), 1,
                                    "Redis caching", "How Redis and a local cache were weighed",
                                    "body", "test/compiler", NOW, "hash", Map.of(), NOW),
                            List.of(), List.of()));
        }
        RunTrace trace = mock(RunTrace.class);

        List<DiscoveredKnowledge> recalled = router.recentEpisodes(5, contextWith(trace));

        assertThat(recalled).extracting(DiscoveredKnowledge::conceptId)
                .containsExactly("episode-new");
        assertThat(recalled.getFirst().scoreType()).isEqualTo("recencyRank");
        assertThat(recalled.getFirst().title()).isEqualTo("Redis caching");
        assertThat(recalled.getFirst().sourceAnchors()).singleElement()
                .satisfies(anchor -> assertThat(anchor)
                        .containsEntry("memoryId", "episode-new")
                        .containsEntry("revisionId", "rev-new"));
        // No embedding call: recall is a time-ordered listing, not a similarity search.
        org.mockito.Mockito.verifyNoInteractions(embeddingProvider);
    }

    @Test
    void recentRecallRefusesAnonymousCallersInsteadOfLeakingAnotherUsersScope() {
        KnowledgeToolRuntimeContext anonymous = new KnowledgeToolRuntimeContext(
                "tenant-a", null, null, null, Set.of("knowledge_search"), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> router.recentEpisodes(5, anonymous))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    private KnowledgeConcept episode(String conceptId, String revisionId, Instant staleAfter) {
        return new KnowledgeConcept(
                conceptId, "tenant-a", "user-a",
                KnowledgeNamespaceType.USER_MEMORY, null,
                KnowledgeConceptType.USER_EPISODE, conceptId, KnowledgeStatus.STABLE,
                revisionId, 1, staleAfter, NOW.minusSeconds(100), NOW.minusSeconds(10));
    }

    private static KnowledgeToolRuntimeContext contextWith(RunTrace trace) {
        return new KnowledgeToolRuntimeContext(
                "tenant-a", "user-a", null, null,
                Set.of("knowledge_search", "knowledge_read", "query_graph"), trace);
    }

    private static KnowledgeHead head(
            KnowledgeProjection projection,
            String currentRevisionId
    ) {
        return head(projection, currentRevisionId, Map.of());
    }

    private static KnowledgeHead head(
            KnowledgeProjection projection,
            String currentRevisionId,
            Map<String, Object> extraMetadata
    ) {
        KnowledgeConcept concept = new KnowledgeConcept(
                projection.conceptId(), projection.tenantId(), projection.userId(),
                projection.namespaceType(), projection.namespaceKey(),
                projection.conceptType(), projection.conceptId(), KnowledgeStatus.STABLE,
                currentRevisionId, 1, null, NOW.minusSeconds(100), NOW.minusSeconds(10));
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(
                projection.conceptType() == KnowledgeConceptType.GRAPH_SPACE
                        ? Map.of("graphId", "graph-a", "schemaId", "schema-a")
                        : Map.of());
        metadata.putAll(extraMetadata);
        KnowledgeRevision revision = new KnowledgeRevision(
                currentRevisionId, projection.conceptId(), 1, projection.title(),
                projection.description(), projection.content(), "test/compiler",
                projection.generatedAt(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                metadata,
                projection.generatedAt());
        return new KnowledgeHead(concept, revision);
    }

    private static KnowledgeProjection projection(
            String conceptId,
            String revisionId,
            KnowledgeConceptType type
    ) {
        KnowledgeNamespaceType namespaceType = switch (type) {
            case GRAPH_SCHEMA, GRAPH_SPACE -> KnowledgeNamespaceType.GRAPH;
            case USER_EPISODE -> KnowledgeNamespaceType.USER_MEMORY;
            case OPERATION_PLAYBOOK -> KnowledgeNamespaceType.OPERATION_MEMORY;
            default -> KnowledgeNamespaceType.COLLECTION;
        };
        String namespaceKey = switch (type) {
            case GRAPH_SCHEMA -> "schema-a";
            case GRAPH_SPACE -> "graph-a:schema-a";
            case USER_EPISODE, OPERATION_PLAYBOOK -> null;
            default -> "collection-a";
        };
        String userId = type == KnowledgeConceptType.USER_EPISODE ? "user-a" : null;
        String tenantId = type == KnowledgeConceptType.GRAPH_SCHEMA
                || type == KnowledgeConceptType.GRAPH_SPACE ? null : "tenant-a";
        return new KnowledgeProjection(
                revisionId, conceptId, revisionId,
                tenantId, userId, namespaceType, namespaceKey,
                type, com.harness.tool.knowledge.index.KnowledgeProjectionMapper.routeTarget(type),
                "cyrene://knowledge/" + conceptId, conceptId, "description", "body",
                NOW,
                type == KnowledgeConceptType.USER_EPISODE ? NOW.minusSeconds(60) : null,
                type == KnowledgeConceptType.OPERATION_PLAYBOOK ? conceptId : null,
                type == KnowledgeConceptType.OPERATION_PLAYBOOK ? 90.0 : null,
                type == KnowledgeConceptType.OPERATION_PLAYBOOK
                        ? List.of("knowledge_read") : List.of(),
                null);
    }

    /** Stub one hybrid search that returned exactly these fused hits. */
    private static KnowledgeProjectionSearchOutcome outcomeOf(KnowledgeProjectionHit... hits) {
        return new KnowledgeProjectionSearchOutcome(
                List.of(hits), KnowledgeRetrievalDiagnostics.empty(0.70, 0.10));
    }
}
