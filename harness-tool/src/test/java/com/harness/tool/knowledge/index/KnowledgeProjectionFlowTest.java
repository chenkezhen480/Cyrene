package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptCursor;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeIndexTaskStatus;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeProjectionFlowTest {

    private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");
    private static final KnowledgeProjectionCollections COLLECTIONS =
            new KnowledgeProjectionCollections(
                    "documents", "catalog", "user_memory", "operation_memory");

    @Test
    void userPreferenceStaysInMysqlWithoutEmbedding() {
        EmbeddingModelProvider embeddingProvider = mock(EmbeddingModelProvider.class);
        KnowledgeProjectionMapper mapper = new KnowledgeProjectionMapper(embeddingProvider);

        assertThat(mapper.map(head(KnowledgeConceptType.USER_PREFERENCE,
                KnowledgeNamespaceType.USER_MEMORY, "user-1", "preference"))).isEmpty();

        verifyNoInteractions(embeddingProvider);
    }

    @Test
    void projectionMapperSupportsCatalogAndBothDedicatedMemoryTypes() {
        KnowledgeProjectionMapper mapper = new KnowledgeProjectionMapper(embeddingProvider());

        for (KnowledgeConceptType type : List.of(
                KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeConceptType.GRAPH_SCHEMA,
                KnowledgeConceptType.GRAPH_SPACE,
                KnowledgeConceptType.USER_EPISODE,
                KnowledgeConceptType.OPERATION_PLAYBOOK)) {
            KnowledgeNamespaceType namespace = switch (type) {
                case GRAPH_SCHEMA, GRAPH_SPACE -> KnowledgeNamespaceType.GRAPH;
                case USER_EPISODE -> KnowledgeNamespaceType.USER_MEMORY;
                case OPERATION_PLAYBOOK -> KnowledgeNamespaceType.OPERATION_MEMORY;
                default -> KnowledgeNamespaceType.COLLECTION;
            };
            String userId = type == KnowledgeConceptType.USER_EPISODE ? "user-1" : null;
            KnowledgeProjection projection = mapper.map(
                    head(type, namespace, userId, type.name().toLowerCase())).orElseThrow();
            assertThat(projection.embedding()).containsExactly(0.1f, 0.2f, 0.3f);
            assertThat(projection.userId()).isEqualTo(userId);
            assertThat(projection.routeTarget())
                    .isEqualTo(KnowledgeProjectionMapper.routeTarget(type));
        }
    }

    @Test
    void conversationSummaryIsTheWikiEntryWhileMemoryBodyUsesItsDedicatedProjection() {
        KnowledgeProjectionMapper mapper = new KnowledgeProjectionMapper(embeddingProvider());
        for (KnowledgeConceptType type : List.of(KnowledgeConceptType.USER_EPISODE, KnowledgeConceptType.OPERATION_PLAYBOOK)) {
            KnowledgeNamespaceType namespace = type == KnowledgeConceptType.USER_EPISODE
                    ? KnowledgeNamespaceType.USER_MEMORY : KnowledgeNamespaceType.OPERATION_MEMORY;
            KnowledgeHead memory = head(type, namespace, type.isUserOwned() ? "user-1" : null, "memory");
            assertThat(mapper.mapCatalog(memory).orElseThrow().content()).isEqualTo("Title\n\nDescription");
            assertThat(mapper.map(memory).orElseThrow().content()).isEqualTo("Body");
        }
    }

    @Test
    void sourceCatalogIndexesFileNameAndMarkdownHeadings() {
        KnowledgeHead base = head(KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeNamespaceType.COLLECTION, null, "document-title");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(base.currentRevision().metadata());
        metadata.put("fileName", "角色元素机制说明.pdf");
        KnowledgeRevision revision = new KnowledgeRevision(
                base.currentRevision().id(), base.concept().id(), 1,
                "Generic Wiki title", "Generic summary",
                "# 芙宁娜气氛值规则\n\n正文。\n\n## 叠层限制\n\n更多正文。",
                "test", NOW, base.currentRevision().contentHash(), metadata, NOW);

        KnowledgeProjection projection = new KnowledgeProjectionMapper(embeddingProvider())
                .mapCatalog(new KnowledgeHead(base.concept(), revision)).orElseThrow();

        assertThat(projection.content()).contains(
                "Generic Wiki title", "角色元素机制说明.pdf", "芙宁娜气氛值规则", "叠层限制", "Generic summary");
    }

    @Test
    void allProjectionCollectionsMustRemainDistinct() {
        assertThatThrownBy(() -> new KnowledgeProjectionCollections(
                "same", "same", "user_memory", "operation_memory"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct");
    }

    @Test
    void conceptTypesUseTheirDedicatedPhysicalCollections() {
        assertThat(COLLECTIONS.projectionCollection(KnowledgeConceptType.SOURCE_DOCUMENT))
                .isEqualTo("catalog");
        assertThat(COLLECTIONS.projectionCollection(KnowledgeConceptType.GRAPH_SCHEMA))
                .isEqualTo("catalog");
        assertThat(COLLECTIONS.projectionCollection(KnowledgeConceptType.GRAPH_SPACE))
                .isEqualTo("catalog");
        assertThat(COLLECTIONS.projectionCollection(KnowledgeConceptType.USER_EPISODE))
                .isEqualTo("user_memory");
        assertThat(COLLECTIONS.projectionCollection(KnowledgeConceptType.OPERATION_PLAYBOOK))
                .isEqualTo("operation_memory");
        assertThatThrownBy(() -> COLLECTIONS.projectionCollection(
                KnowledgeConceptType.USER_PREFERENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a vector projection");
    }

    @Test
    void milvusScopesEpisodesExactlyAndSharesOperationKnowledgeByTenant() {
        String episodeFilter = MilvusKnowledgeProjectionStore.searchFilter(
                new KnowledgeProjectionSearch(
                        "memory", new float[]{0.1f}, "tenant-a", "user-a",
                        Set.of(KnowledgeConceptType.USER_EPISODE),
                        20, 20, 0.70, 0.10, 60));
        String operationFilter = MilvusKnowledgeProjectionStore.searchFilter(
                new KnowledgeProjectionSearch(
                        "memory", new float[]{0.1f}, "tenant-a", "user-a",
                        Set.of(KnowledgeConceptType.OPERATION_PLAYBOOK),
                        20, 20, 0.70, 0.10, 60));

        assertThat(episodeFilter)
                .contains("concept_type in [\"USER_EPISODE\"]")
                .contains("user_id == \"user-a\"")
                .contains("tenant_id == \"tenant-a\"")
                .doesNotContain("tenant_id is null");
        assertThat(operationFilter)
                .contains("concept_type in [\"OPERATION_PLAYBOOK\"]")
                .contains("tenant_id == \"tenant-a\"")
                .contains("tenant_id is null");
    }

    @Test
    void identitySearchFiltersTheExactTenantAndNamespaceBeforeRetrieval() {
        String filter = MilvusKnowledgeProjectionStore.searchFilter(
                new KnowledgeProjectionSearch(
                        "guide", new float[]{0.1f}, "tenant-a", null,
                        KnowledgeNamespaceType.COLLECTION, "documents", true,
                        Set.of(KnowledgeConceptType.SOURCE_DOCUMENT),
                        20, 5, 0.70, 0.10, 60));

        assertThat(filter)
                .contains("tenant_id == \"tenant-a\"")
                .doesNotContain("tenant_id is null")
                .contains("namespace_type == \"COLLECTION\"")
                .contains("namespace_key == \"documents\"");
    }

    @Test
    void catalogTaskPreservesItsVersionContentAndHistory() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        org.mockito.Mockito.doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(repository).withAuthorityLock(any(), any());
        KnowledgeProjectionStore store = mock(KnowledgeProjectionStore.class);
        KnowledgeHead current = head(KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeNamespaceType.COLLECTION, null, "document");
        when(repository.findAuthorityById(current.concept().id())).thenReturn(Optional.of(current));
        when(repository.findSnapshot(current.currentRevision().id())).thenReturn(
                new com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot(current.concept().conceptType(),
                        current.concept().namespaceKey(), current.currentRevision(), List.of(), List.of()));
        KnowledgeIndexProjector projector = new KnowledgeIndexProjector(
                repository, store,
                new KnowledgeProjectionMapper(embeddingProvider()));

        projector.project(task(current.concept().id(), current.currentRevision().id(),
                KnowledgeIndexOperation.UPSERT_CURRENT));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeProjection>> projections =
                ArgumentCaptor.forClass(List.class);
        verify(store).upsertCatalog(projections.capture());
        assertThat(projections.getValue()).singleElement()
                .extracting(KnowledgeProjection::revisionId)
                .isEqualTo(current.currentRevision().id());
    }

    @Test
    void deprecatedCatalogConceptIsRemovedFromProjection() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        org.mockito.Mockito.doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(repository).withAuthorityLock(any(), any());
        KnowledgeProjectionStore store = mock(KnowledgeProjectionStore.class);
        KnowledgeHead deprecated = head(KnowledgeConceptType.GRAPH_SCHEMA,
                KnowledgeNamespaceType.GRAPH, null, "schema", KnowledgeStatus.DEPRECATED);
        when(repository.findAuthorityById(deprecated.concept().id()))
                .thenReturn(Optional.of(deprecated));
        KnowledgeIndexProjector projector = new KnowledgeIndexProjector(
                repository, store,
                new KnowledgeProjectionMapper(embeddingProvider()));

        projector.project(task(deprecated.concept().id(), deprecated.currentRevision().id(),
                KnowledgeIndexOperation.UPSERT_CURRENT));

        verify(store).deleteConcept(deprecated.concept().id());
        verify(store, never()).upsert(any());
    }

    @Test
    void draftMemoryContentIsArchivedWhileAuthorityRemainsDraft() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        org.mockito.Mockito.doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(repository).withAuthorityLock(any(), any());
        KnowledgeProjectionStore store = mock(KnowledgeProjectionStore.class);
        EmbeddingModelProvider embeddingProvider = embeddingProvider();
        KnowledgeHead draft = head(KnowledgeConceptType.USER_EPISODE,
                KnowledgeNamespaceType.USER_MEMORY, "user-1", "episode",
                KnowledgeStatus.DRAFT);
        when(repository.findAuthorityById(draft.concept().id())).thenReturn(Optional.of(draft));
        when(repository.findSnapshot(draft.currentRevision().id())).thenReturn(
                new com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot(draft.concept().conceptType(),
                        draft.concept().namespaceKey(), draft.currentRevision(), List.of(), List.of()));
        KnowledgeIndexProjector projector = new KnowledgeIndexProjector(
                repository, store, new KnowledgeProjectionMapper(embeddingProvider));

        projector.project(task(draft.concept().id(), draft.currentRevision().id(),
                KnowledgeIndexOperation.UPSERT_CURRENT));

        verify(store, never()).deleteConcept(draft.concept().id());
        verify(store).upsert(any());
    }

    @Test
    void catalogReindexUsesStableCursorAndReconcilesStaleRows() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        org.mockito.Mockito.doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(repository).withAuthorityLock(any(), any());
        KnowledgeProjectionStore store = mock(KnowledgeProjectionStore.class);
        KnowledgeHead current = head(KnowledgeConceptType.SOURCE_DOCUMENT,
                KnowledgeNamespaceType.COLLECTION, null, "document");
        when(repository.findPage(null, null, KnowledgeNamespaceType.COLLECTION,
                KnowledgeConceptType.SOURCE_DOCUMENT, null, null, 1))
                .thenReturn(new PageResponse<>(List.of(current.concept()),
                        new PageInfo(1, "next", true)));
        when(repository.findAuthorityById(current.concept().id())).thenReturn(Optional.of(current));
        when(repository.findSnapshot(current.currentRevision().id())).thenReturn(
                new com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot(current.concept().conceptType(),
                        current.concept().namespaceKey(), current.currentRevision(), List.of(), List.of()));
        when(repository.findAuthorityById("missing")).thenReturn(Optional.empty());
        when(store.findIdentityPage(null, 2)).thenReturn(new PageResponse<>(
                List.of(
                        new KnowledgeProjectionIdentity(
                                current.currentRevision().id(), current.concept().id()),
                        new KnowledgeProjectionIdentity("orphan-revision", "missing")),
                new PageInfo(2, "", false)));
        KnowledgeReindexService service = new KnowledgeReindexService(
                repository, store,
                new KnowledgeProjectionMapper(embeddingProvider()));

        KnowledgeReindexService.ReindexPageResult reindexed = service.reindexPage(
                new KnowledgeReindexService.ReindexScope(
                        null, null, KnowledgeNamespaceType.COLLECTION,
                        KnowledgeConceptType.SOURCE_DOCUMENT),
                null, 1);
        KnowledgeReindexService.ReconcilePageResult reconciled =
                service.reconcileProjectionPage(null, 2);

        assertThat(reindexed.upserted()).isOne();
        assertThat(reindexed.nextCursor()).isEqualTo(new KnowledgeConceptCursor(
                current.concept().updatedAt(), current.concept().id()));
        assertThat(reconciled.deleted()).isOne();
        verify(store).deleteRevision("orphan-revision");
    }

    private static EmbeddingModelProvider embeddingProvider() {
        EmbeddingModelProvider provider = mock(EmbeddingModelProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.dimension()).thenReturn(3);
        when(provider.embed(any(String.class)))
                .thenReturn(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}));
        return provider;
    }

    private static KnowledgeHead head(
            KnowledgeConceptType type,
            KnowledgeNamespaceType namespaceType,
            String userId,
            String logicalKey
    ) {
        return head(type, namespaceType, userId, logicalKey, KnowledgeStatus.STABLE);
    }

    private static KnowledgeHead head(
            KnowledgeConceptType type,
            KnowledgeNamespaceType namespaceType,
            String userId,
            String logicalKey,
            KnowledgeStatus status
    ) {
        String conceptId = "concept-" + logicalKey;
        String revisionId = "revision-" + logicalKey;
        String namespaceKey = namespaceType == KnowledgeNamespaceType.USER_MEMORY
                || namespaceType == KnowledgeNamespaceType.OPERATION_MEMORY
                ? null : "scope-1";
        KnowledgeConcept concept = new KnowledgeConcept(
                conceptId, null, userId, namespaceType, namespaceKey, type, logicalKey,
                status, revisionId, 1, null, NOW, NOW);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("resourceUri", "cyrene://knowledge/" + conceptId);
        if (type == KnowledgeConceptType.USER_EPISODE) {
            metadata.put("eventTime", NOW.toString());
        } else if (type == KnowledgeConceptType.OPERATION_PLAYBOOK) {
            metadata.put("qualityScore", 90.0);
            metadata.put("requiredTools", List.of("knowledge_read"));
        }
        KnowledgeRevision revision = new KnowledgeRevision(
                revisionId, conceptId, 1, "Title", "Description", "Body", "test", NOW,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Map.copyOf(metadata), NOW);
        return new KnowledgeHead(concept, revision);
    }

    private static KnowledgeIndexTask task(
            String conceptId,
            String revisionId,
            KnowledgeIndexOperation operation
    ) {
        return new KnowledgeIndexTask(
                1L, conceptId, revisionId, operation,
                KnowledgeIndexTaskStatus.IN_PROGRESS, 1, NOW, NOW, null, null, NOW);
    }
}
