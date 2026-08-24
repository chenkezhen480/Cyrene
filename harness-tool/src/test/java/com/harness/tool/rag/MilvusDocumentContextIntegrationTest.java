package com.harness.tool.rag;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.PageResponse;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.tool.knowledge.KnowledgeChunkSummary;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.utility.request.FlushReq;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MilvusDocumentContextIntegrationTest {

    private static final String PHYSICAL_COLLECTION = "phase6_context_integration";
    private static final String LOGICAL_COLLECTION = "tenant_manuals";
    private static final String OTHER_COLLECTION = "other_tenant";
    private static final String DOCUMENT_ID = "upload_manual_v1";

    private static MilvusClientV2 client;
    private static MilvusVectorStore store;

    @BeforeAll
    static void setUp() {
        assumeTrue(Boolean.getBoolean("milvus.integration"));
        EnvConfig.init(Map.of(
                EnvKey.RAG_URL, System.getProperty("milvus.url", "http://localhost:19530"),
                EnvKey.RAG_COLLECTION, PHYSICAL_COLLECTION,
                EnvKey.MODEL_EMBEDDING_DIM, "4",
                EnvKey.RAG_SCORE_THRESHOLD, "0.0"
        ));
        MilvusConnectionPool.init();
        client = MilvusConnectionPool.getClient();
        dropTestCollection();
        MilvusCollectionInitializer.ensureCollection();
        store = new MilvusVectorStore();
        store.upsert(LOGICAL_COLLECTION, fixedCorpus());
        client.flush(FlushReq.builder().collectionNames(List.of(PHYSICAL_COLLECTION)).build());
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            dropTestCollection();
        }
        MilvusConnectionPool.shutdown();
    }

    @Test
    void recordsExplicitContextBenchmarkAndEnforcesCollectionIsolation() {
        long searchStarted = System.nanoTime();
        List<VectorStore.Document> hits = store.searchVector(
                LOGICAL_COLLECTION, new float[]{1.0f, 0.0f, 0.0f, 0.0f}, 1);
        long searchMicros = elapsedMicros(searchStarted);

        assertThat(hits).hasSize(1);
        VectorStore.Document anchor = hits.getFirst();
        assertThat(anchor.metadata()).containsEntry("document_id", DOCUMENT_ID);
        assertThat(anchor.chunkIndex()).isEqualTo(1);

        long contextStarted = System.nanoTime();
        List<VectorStore.Document> context = store.readDocumentWindow(
                LOGICAL_COLLECTION, DOCUMENT_ID, anchor.chunkIndex(), 1, 1);
        long contextMicros = elapsedMicros(contextStarted);

        assertThat(context).extracting(VectorStore.Document::chunkIndex)
                .containsExactly(0, 1, 2);
        assertThat(context).allSatisfy(document -> {
            assertThat(document.metadata()).containsEntry("document_id", DOCUMENT_ID);
            assertThat(document.metadata()).containsKey("heading_path");
        });
        assertThat(store.readDocumentWindow(
                OTHER_COLLECTION, DOCUMENT_ID, anchor.chunkIndex(), 1, 1)).isEmpty();
        assertThat(store.searchVector(
                OTHER_COLLECTION, new float[]{1.0f, 0.0f, 0.0f, 0.0f}, 5)).isEmpty();

        String baselineText = anchor.content();
        String explicitText = context.stream()
                .map(VectorStore.Document::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();
        int baselineEvidence = evidenceCount(baselineText);
        int explicitEvidence = evidenceCount(explicitText);
        int baselineTokens = UnicodeAwareTextTokenEstimator.INSTANCE.estimate(baselineText);
        int explicitTokens = UnicodeAwareTextTokenEstimator.INSTANCE.estimate(explicitText);

        assertThat(baselineEvidence).isEqualTo(1);
        assertThat(explicitEvidence).isEqualTo(3);
        System.out.printf(
                "PHASE6_BENCHMARK baselineEvidence=%d/3 explicitEvidence=%d/3 "
                        + "extraCalls=1 searchLatencyMicros=%d contextLatencyMicros=%d "
                        + "baselineTokens=%d explicitTokens=%d estimator=%s%n",
                baselineEvidence,
                explicitEvidence,
                searchMicros,
                contextMicros,
                baselineTokens,
                explicitTokens,
                UnicodeAwareTextTokenEstimator.INSTANCE.strategyName());
    }

    @Test
    void fileNameFilterAndPrimaryKeyCursorReturnEveryMatchingChunkOnce() {
        PageResponse<KnowledgeChunkSummary> firstPage = store.listKnowledgeChunks(
                LOGICAL_COLLECTION, "upload-manual.md", 2, "");

        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.items())
                .extracting(KnowledgeChunkSummary::fileName)
                .containsOnly("upload-manual.md");
        assertThat(firstPage.pageInfo().hasMore()).isTrue();
        assertThat(firstPage.pageInfo().nextCursor()).isNotBlank();

        PageResponse<KnowledgeChunkSummary> secondPage = store.listKnowledgeChunks(
                LOGICAL_COLLECTION,
                "upload-manual.md",
                2,
                firstPage.pageInfo().nextCursor());

        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.pageInfo().hasMore()).isFalse();
        assertThat(secondPage.pageInfo().nextCursor()).isEmpty();
        assertThat(java.util.stream.Stream.concat(
                        firstPage.items().stream(), secondPage.items().stream())
                .map(KnowledgeChunkSummary::id)
                .distinct())
                .hasSize(3);
        assertThat(store.listKnowledgeChunks(
                OTHER_COLLECTION, "upload-manual.md", 2, "").items()).isEmpty();
    }

    @Test
    void logicalCollectionCrudNeverWidensToThePhysicalCollection() {
        String isolatedCollection = "crud_isolation";
        String chunkId = "crud-chunk-1";
        try {
            store.upsert(isolatedCollection, List.of(new VectorStore.Document(
                    chunkId,
                    "original content",
                    "crud.md",
                    0.0,
                    Map.of("document_id", "crud-document"),
                    new float[]{0.0f, 0.0f, 1.0f, 0.0f},
                    0)));
            flush();

            assertThat(store.listCollections(100, "").items())
                    .contains(LOGICAL_COLLECTION, isolatedCollection);
            assertThat(store.getById(isolatedCollection, chunkId)).isNotNull();
            assertThat(store.getById(LOGICAL_COLLECTION, chunkId)).isNull();
            assertThat(store.deleteById(LOGICAL_COLLECTION, chunkId)).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    store.upsert(LOGICAL_COLLECTION, List.of(new VectorStore.Document(
                            chunkId,
                            "must not cross collections",
                            "forged.md",
                            0.0,
                            Map.of(),
                            new float[]{0.0f, 1.0f, 0.0f, 0.0f},
                            0))))
                    .isInstanceOf(IllegalArgumentException.class);

            store.updateContent(
                    isolatedCollection,
                    chunkId,
                    "updated content",
                    new float[]{0.0f, 0.0f, 0.9f, 0.1f});
            flush();
            assertThat(store.getById(isolatedCollection, chunkId).content())
                    .isEqualTo("updated content");

            store.delete(isolatedCollection);
            flush();
            assertThat(store.getById(isolatedCollection, chunkId)).isNull();
            assertThat(store.getById(LOGICAL_COLLECTION, "fixture-0")).isNotNull();
        } finally {
            store.delete(isolatedCollection);
            flush();
        }
    }

    private static List<VectorStore.Document> fixedCorpus() {
        return List.of(
                document(0, "定义：单个上传文件的大小上限为 20 MB。", new float[]{0.6f, 0.2f, 0.0f, 0.0f}),
                document(1, "上传接口会在接收到文件后执行大小检查。", new float[]{1.0f, 0.0f, 0.0f, 0.0f}),
                document(2, "超过上限时返回 FILE_TOO_LARGE 错误。", new float[]{0.7f, 0.1f, 0.0f, 0.0f}),
                new VectorStore.Document(
                        "fixture-billing",
                        "账单标题不应命中文件名过滤。",
                        "billing-guide.md",
                        0.0,
                        Map.of(
                                "document_id", "billing-guide-v1",
                                "heading_path", List.of("Billing")),
                        new float[]{0.0f, 1.0f, 0.0f, 0.0f},
                        0)
        );
    }

    private static VectorStore.Document document(int chunkIndex, String content, float[] embedding) {
        return new VectorStore.Document(
                "fixture-" + chunkIndex,
                content,
                "upload-manual.md",
                0.0,
                Map.of(
                        "document_id", DOCUMENT_ID,
                        "heading_path", List.of("上传", "大小限制")),
                embedding,
                chunkIndex);
    }

    private static int evidenceCount(String text) {
        int count = 0;
        if (text.contains("20 MB")) {
            count++;
        }
        if (text.contains("大小检查")) {
            count++;
        }
        if (text.contains("FILE_TOO_LARGE")) {
            count++;
        }
        return count;
    }

    private static long elapsedMicros(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000L;
    }

    private static void flush() {
        client.flush(FlushReq.builder().collectionNames(List.of(PHYSICAL_COLLECTION)).build());
    }

    private static void dropTestCollection() {
        if (client.hasCollection(HasCollectionReq.builder()
                .collectionName(PHYSICAL_COLLECTION)
                .build())) {
            client.dropCollection(DropCollectionReq.builder()
                    .collectionName(PHYSICAL_COLLECTION)
                    .build());
        }
    }
}
