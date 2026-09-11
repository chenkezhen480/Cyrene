package com.harness.tool.knowledge.index;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import com.harness.tool.rag.MilvusConnectionPool;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.FlushReq;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MilvusKnowledgeProjectionIT {

    private static final int DIMENSION = 1024;
    private static final String DOCUMENT_COLLECTION = "cyrene_test";
    private static final String CATALOG_COLLECTION = "cyrene_knowledge_catalog";
    private static final String USER_COLLECTION = "cyrene_user_knowledge";
    private static final String OPERATION_COLLECTION = "cyrene_operation_knowledge";
    private static final String CATALOG_CONCEPT = "catalog-only-it-concept";
    private static final String GRAPH_SPACE_CONCEPT = "graph-space-it-concept";
    private static final List<String> SCOPE_CONCEPTS = List.of(
            "episode-user-a-it", "episode-user-b-it",
            "playbook-tenant-a-it", "playbook-tenant-b-it");
    private static MilvusClientV2 client;
    private static MilvusKnowledgeProjectionStore store;

    @BeforeAll
    static void setUp() {
        assumeTrue(Boolean.getBoolean("milvus.integration"));
        EnvConfig.init(Map.of(
                EnvKey.RAG_PROVIDER, "milvus",
                EnvKey.RAG_URL, System.getProperty("milvus.url", "http://localhost:19530"),
                EnvKey.RAG_DATABASE, "cyrene_test",
                EnvKey.RAG_COLLECTION, DOCUMENT_COLLECTION,
                EnvKey.KNOWLEDGE_CATALOG_COLLECTION, CATALOG_COLLECTION,
                EnvKey.MEMORY_USER_KNOWLEDGE_COLLECTION, USER_COLLECTION,
                EnvKey.MEMORY_OPERATION_KNOWLEDGE_COLLECTION, OPERATION_COLLECTION));
        MilvusConnectionPool.init();
        client = MilvusConnectionPool.getClient();
        store = new MilvusKnowledgeProjectionStore(client);
        store.initialize(new KnowledgeProjectionCollections(
                DOCUMENT_COLLECTION, CATALOG_COLLECTION,
                USER_COLLECTION, OPERATION_COLLECTION), DIMENSION);
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.deleteConcept(CATALOG_CONCEPT);
            store.deleteConcept(GRAPH_SPACE_CONCEPT);
            flush();
        }
        MilvusConnectionPool.shutdown();
    }

    @Test
    void initializesAllProjectionCollectionsAndSearchesCatalog() {
        store.upsert(List.of(
                catalogProjection(),
                projection(GRAPH_SPACE_CONCEPT, null, null,
                        KnowledgeNamespaceType.GRAPH,
                        KnowledgeConceptType.GRAPH_SPACE,
                        "Graph space projection")));
        flush();

        assertThat(client.listCollections().getCollectionNames())
                .contains(CATALOG_COLLECTION, USER_COLLECTION, OPERATION_COLLECTION);
        assertThat(store.findIdentityPage(null, 20).items())
                .contains(new KnowledgeProjectionIdentity(
                        CATALOG_CONCEPT + "-r1", CATALOG_CONCEPT));

        List<KnowledgeProjectionHit> hits = store.searchHybrid(
                new KnowledgeProjectionSearch(
                        "Catalog topic projection", vector(), null, null,
                        Set.of(KnowledgeConceptType.SOURCE_DOCUMENT),
                        20, 20, 0.70, 0.10, 60));

        assertThat(hits).extracting(hit -> hit.projection().conceptId())
                .contains(CATALOG_CONCEPT);

        List<KnowledgeProjectionHit> graphHits = store.searchHybrid(
                new KnowledgeProjectionSearch(
                        "Graph space projection", vector(), "tenant-a", null,
                        Set.of(KnowledgeConceptType.GRAPH_SPACE),
                        20, 20, 0.70, 0.10, 60));
        assertThat(graphHits).extracting(hit -> hit.projection().conceptId())
                .contains(GRAPH_SPACE_CONCEPT);
    }

    @Test
    void dedicatedMemoryCollectionsEnforceOwnerAndTenantScope() {
        store.upsert(List.of(
                projection(SCOPE_CONCEPTS.get(0), "tenant-a", "user-a",
                        KnowledgeNamespaceType.USER_MEMORY,
                        KnowledgeConceptType.USER_EPISODE, "Scoped memory"),
                projection(SCOPE_CONCEPTS.get(1), "tenant-a", "user-b",
                        KnowledgeNamespaceType.USER_MEMORY,
                        KnowledgeConceptType.USER_EPISODE, "Scoped memory"),
                projection(SCOPE_CONCEPTS.get(2), "tenant-a", null,
                        KnowledgeNamespaceType.OPERATION_MEMORY,
                        KnowledgeConceptType.OPERATION_PLAYBOOK, "Scoped memory"),
                projection(SCOPE_CONCEPTS.get(3), "tenant-b", null,
                        KnowledgeNamespaceType.OPERATION_MEMORY,
                        KnowledgeConceptType.OPERATION_PLAYBOOK, "Scoped memory")));
        flush();

        try {
            List<KnowledgeProjectionHit> scoped = store.searchHybrid(
                    new KnowledgeProjectionSearch(
                            "Scoped memory", vector(), "tenant-a", "user-a",
                            Set.of(KnowledgeConceptType.USER_EPISODE,
                                    KnowledgeConceptType.OPERATION_PLAYBOOK),
                            20, 20, 0.70, 0.10, 60));
            assertThat(scoped).extracting(hit -> hit.projection().conceptId())
                    .contains(SCOPE_CONCEPTS.get(0), SCOPE_CONCEPTS.get(2))
                    .doesNotContain(SCOPE_CONCEPTS.get(1), SCOPE_CONCEPTS.get(3));

            List<KnowledgeProjectionHit> unauthenticatedEpisodes = store.searchHybrid(
                    new KnowledgeProjectionSearch(
                            "Scoped memory", vector(), "tenant-a", null,
                            Set.of(KnowledgeConceptType.USER_EPISODE),
                            20, 20, 0.70, 0.10, 60));
            assertThat(unauthenticatedEpisodes).isEmpty();
        } finally {
            SCOPE_CONCEPTS.forEach(store::deleteConcept);
            flush();
        }
    }

    private static KnowledgeProjection catalogProjection() {
        return projection(CATALOG_CONCEPT, null, null,
                KnowledgeNamespaceType.COLLECTION,
                KnowledgeConceptType.SOURCE_DOCUMENT,
                "Catalog topic projection");
    }

    private static KnowledgeProjection projection(
            String conceptId,
            String tenantId,
            String userId,
            KnowledgeNamespaceType namespaceType,
            KnowledgeConceptType conceptType,
            String content
    ) {
        String revisionId = conceptId + "-r1";
        return new KnowledgeProjection(
                revisionId,
                conceptId,
                revisionId,
                tenantId,
                userId,
                namespaceType,
                namespaceType == KnowledgeNamespaceType.COLLECTION
                        ? DOCUMENT_COLLECTION
                        : namespaceType == KnowledgeNamespaceType.GRAPH
                                ? conceptType == KnowledgeConceptType.GRAPH_SPACE
                                        ? "graph-it:schema-it" : "schema-it"
                                : null,
                conceptType,
                KnowledgeProjectionMapper.routeTarget(conceptType),
                "cyrene://knowledge/" + conceptId,
                content,
                content,
                content,
                Instant.parse("2026-09-02T03:00:00Z"),
                conceptType == KnowledgeConceptType.USER_EPISODE
                        ? Instant.parse("2026-09-02T02:00:00Z") : null,
                conceptType == KnowledgeConceptType.OPERATION_PLAYBOOK
                        ? conceptId : null,
                conceptType == KnowledgeConceptType.OPERATION_PLAYBOOK
                        ? 90.0 : null,
                conceptType == KnowledgeConceptType.OPERATION_PLAYBOOK
                        ? List.of("knowledge_read") : List.of(),
                vector());
    }

    private static float[] vector() {
        float[] vector = new float[DIMENSION];
        vector[0] = 1.0f;
        return vector;
    }

    private static void flush() {
        client.flush(FlushReq.builder().collectionNames(List.of(
                CATALOG_COLLECTION, USER_COLLECTION, OPERATION_COLLECTION)).build());
    }
}
