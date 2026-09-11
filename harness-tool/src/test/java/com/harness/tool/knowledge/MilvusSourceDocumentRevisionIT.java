package com.harness.tool.knowledge;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.tool.rag.MilvusCollectionInitializer;
import com.harness.tool.rag.MilvusConnectionPool;
import com.harness.tool.rag.MilvusVectorStore;
import com.harness.tool.rag.VectorStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.FlushReq;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MilvusSourceDocumentRevisionIT {

    private static final String PHYSICAL_COLLECTION = "cyrene_test";
    private static final String LOGICAL_COLLECTION = "phase6_it_documents";
    private static final String DOCUMENT_ID = "phase6-it-document";
    private static final String REVISION_ONE = "phase6-it-revision-1";
    private static final String REVISION_TWO = "phase6-it-revision-2";
    private static final int DIMENSION = 1024;

    private static MilvusClientV2 client;
    private static MilvusVectorStore store;

    @BeforeAll
    static void setUp() {
        assumeTrue(Boolean.getBoolean("milvus.integration"));
        EnvConfig.init(Map.of(
                EnvKey.RAG_PROVIDER, "milvus",
                EnvKey.RAG_URL, System.getProperty("milvus.url", "http://localhost:19530"),
                EnvKey.RAG_DATABASE, "cyrene_test",
                EnvKey.RAG_COLLECTION, PHYSICAL_COLLECTION,
                EnvKey.RAG_SCORE_THRESHOLD, "0.0"));
        MilvusConnectionPool.init();
        client = MilvusConnectionPool.getClient();
        MilvusCollectionInitializer.ensureCollection(DIMENSION);
        store = new MilvusVectorStore();
        store.delete(LOGICAL_COLLECTION);
        flush();
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.delete(LOGICAL_COLLECTION);
            flush();
            assertThat(store.listKnowledgeChunks(LOGICAL_COLLECTION, "", 100, "").items())
                    .isEmpty();
        }
        MilvusConnectionPool.shutdown();
    }

    @Test
    void wholeRevisionReplacementIsStableAndLeavesNoOldRevisionChunks() {
        List<VectorStore.Document> firstRevision = List.of(
                chunk(REVISION_ONE, 0, "first zero"),
                chunk(REVISION_ONE, 1, "first one"));
        store.upsert(LOGICAL_COLLECTION, firstRevision);
        store.upsert(LOGICAL_COLLECTION, firstRevision);
        flush();
        assertThat(store.listKnowledgeChunks(
                LOGICAL_COLLECTION, "manual-v1.md", 10, "").items()).hasSize(2);

        List<VectorStore.Document> secondRevision = List.of(
                chunk(REVISION_TWO, 0, "second zero"),
                chunk(REVISION_TWO, 1, "second one"),
                chunk(REVISION_TWO, 2, "second two"));
        store.upsert(LOGICAL_COLLECTION, secondRevision);
        store.deleteDocumentRevision(LOGICAL_COLLECTION, DOCUMENT_ID, REVISION_ONE);
        flush();

        assertThat(store.getById(
                LOGICAL_COLLECTION, firstRevision.getFirst().id())).isNull();
        assertThat(store.readDocumentWindow(
                LOGICAL_COLLECTION, DOCUMENT_ID, REVISION_TWO, 1, 1, 1))
                .extracting(VectorStore.Document::content)
                .containsExactly("second zero", "second one", "second two");
        assertThat(store.readDocumentWindow(
                LOGICAL_COLLECTION, DOCUMENT_ID, REVISION_TWO, 1, 1, 1))
                .allSatisfy(document -> assertThat(document.metadata())
                        .containsEntry("revision_id", REVISION_TWO)
                        .containsEntry("artifact_id", "phase6-it-artifact"));
    }

    private static VectorStore.Document chunk(
            String revisionId,
            int chunkIndex,
            String content
    ) {
        String id = KnowledgeIdentity.documentChunkId(
                revisionId, chunkIndex, KnowledgeIdentity.sha256(content));
        float[] embedding = new float[DIMENSION];
        embedding[chunkIndex] = 1.0f;
        return new VectorStore.Document(
                id, content,
                revisionId.equals(REVISION_ONE) ? "manual-v1.md" : "manual-v2.md",
                0,
                Map.of(
                        "document_id", DOCUMENT_ID,
                        "concept_id", DOCUMENT_ID,
                        "revision_id", revisionId,
                        "artifact_id", "phase6-it-artifact",
                        "chunk_index", chunkIndex,
                        "heading_path", List.of("Manual")),
                embedding,
                chunkIndex);
    }

    private static void flush() {
        if (client != null) {
            client.flush(FlushReq.builder()
                    .collectionNames(List.of(PHYSICAL_COLLECTION)).build());
        }
    }
}
