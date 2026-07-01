package com.harness.preprocess.rag;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.env.MilvusConnectionPool;
import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Milvus 向量存储实现（v2 API）。
 * 纯检索层，不负责 collection 初始化（由 MilvusCollectionInitializer 处理）。
 *
 * 支持：向量检索、BM25 全文检索、混合检索（RRF 融合）、chunk 链表。
 */
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final MilvusClientV2 client;
    private final String collectionName;
    private final String logicalCollection;
    private final int topK;
    private final double scoreThreshold;
    private final double bm25Weight;
    private final EmbeddingModelProvider embeddingProvider;

    public MilvusVectorStore() {
        this(null);
    }

    public MilvusVectorStore(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        this.collectionName = cfg.getString(EnvKey.RAG_COLLECTION, "knowledge_documents");
        this.logicalCollection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        this.topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        this.scoreThreshold = cfg.getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.7);
        this.bm25Weight = cfg.getDouble(EnvKey.RAG_BM25_WEIGHT, 0.3);
        this.embeddingProvider = embeddingProvider;
        this.client = MilvusConnectionPool.getClient();
    }

    // ==================== 1. 基础管理 ====================

    @Override
    public void upsert(String collection, List<Document> docs) {
        String coll = collection != null ? collection : logicalCollection;

        // 生成 UUID 作为 id
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            ids.add(UUID.randomUUID().toString().replace("-", ""));
        }

        // 一次性插入所有 chunks（含 prev/next 链接）
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            if (doc.embedding() == null || doc.embedding().length == 0) {
                throw new IllegalArgumentException("Milvus upsert requires embedding for all documents. Doc at index " + i + " has no embedding.");
            }
            if (doc.content() == null || doc.content().isBlank()) {
                throw new IllegalArgumentException("Milvus upsert requires content for all documents. Doc at index " + i + " has no content.");
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", ids.get(i));
            row.addProperty("content", doc.content());
            row.addProperty("source", doc.source());
            row.addProperty("collection", coll);
            row.add("embedding", floatArrayToJsonArray(doc.embedding()));
            row.addProperty("chunk_index", doc.chunkIndex() >= 0 ? doc.chunkIndex() : i);
            row.addProperty("metadata", doc.metadata() != null ? mapToJson(doc.metadata()) : "{}");
            if (i > 0) {
                row.addProperty("prev_chunk_id", ids.get(i - 1));
            }
            if (i < docs.size() - 1) {
                row.addProperty("next_chunk_id", ids.get(i + 1));
            }
            rows.add(row);
        }

        client.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());

        log.info("[Milvus] Inserted {} linked documents into collection '{}'", docs.size(), coll);
    }

    @Override
    public void delete(String collection) {
        client.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter("collection == \"" + escapeExpr(collection) + "\"")
                .build());
        log.info("[Milvus] Deleted documents from collection '{}'", collection);
    }

    @Override
    public boolean deleteById(String id) {
        try {
            client.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter("id == \"" + escapeExpr(id) + "\"")
                    .build());
            log.info("[Milvus] Deleted document {}", id);
            return true;
        } catch (Exception e) {
            log.debug("[Milvus] Failed to delete document {}: {}", id, e.getMessage());
            return false;
        }
    }

    // ==================== 2. 查询能力 ====================

    @Override
    public Document getById(String id) {
        try {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("id == \"" + escapeExpr(id) + "\"")
                    .outputFields(List.of("content", "source"))
                    .limit(1L)
                    .build());
            if (!resp.getQueryResults().isEmpty()) {
                Map<String, Object> entity = resp.getQueryResults().get(0).getEntity();
                return new Document(id,
                        entity.get("content") != null ? entity.get("content").toString() : "",
                        entity.get("source") != null ? entity.get("source").toString() : "",
                        1.0, null);
            }
        } catch (Exception e) {
            log.debug("[Milvus] Failed to get by id {}: {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public List<Document> listByCollection(String collection) {
        try {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("collection == \"" + escapeExpr(collection) + "\"")
                    .outputFields(List.of("id", "content", "source", "chunk_index"))
                    .limit(1000L)
                    .build());
            List<Document> docs = new ArrayList<>();
            for (QueryResp.QueryResult row : resp.getQueryResults()) {
                Map<String, Object> entity = row.getEntity();
                docs.add(new Document(
                        entity.get("id") != null ? entity.get("id").toString() : "",
                        entity.get("content") != null ? entity.get("content").toString() : "",
                        entity.get("source") != null ? entity.get("source").toString() : "",
                        0,
                        Map.of("chunk_index", entity.get("chunk_index") != null
                                ? entity.get("chunk_index").toString() : "")));
            }
            log.info("[Milvus] Listed {} documents in collection '{}'", docs.size(), collection);
            return docs;
        } catch (Exception e) {
            log.error("[Milvus] Failed to list collection '{}': {}", collection, e.getMessage(), e);
            return List.of();
        }
    }

    // ==================== 3. 检索能力 ====================

    @Override
    public List<Document> searchVector(String collection, float[] embedding, int topK) {
        if (embedding == null || embedding.length == 0) {
            log.warn("Empty embedding, skipping Milvus vector search");
            return List.of();
        }
        try {
            SearchResp resp = client.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField("embedding")
                    .data(List.of(new FloatVec(toFloatList(embedding))))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.COSINE)
                    .filter("collection == \"" + escapeExpr(collection) + "\"")
                    .outputFields(List.of("content", "source"))
                    .build());
            return extractResults(resp);
        } catch (Exception e) {
            log.error("[Milvus] Vector search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<Document> searchKeyword(String collection, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        try {
            SearchResp resp = client.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField("sparse_content")
                    .data(List.of(new EmbeddedText(query)))
                    .topK(topK)
                    .metricType(IndexParam.MetricType.BM25)
                    .filter("collection == \"" + escapeExpr(collection) + "\"")
                    .outputFields(List.of("content", "source"))
                    .build());
            return extractResults(resp);
        } catch (Exception e) {
            log.error("[Milvus] BM25 search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<Document> searchHybrid(String collection, String query, float[] embedding, int topK) {
        if (embedding == null || embedding.length == 0 || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            AnnSearchReq denseReq = AnnSearchReq.builder()
                    .vectorFieldName("embedding")
                    .vectors(List.of(new FloatVec(toFloatList(embedding))))
                    .topK(topK * 2)
                    .metricType(IndexParam.MetricType.COSINE)
                    .expr("collection == \"" + escapeExpr(collection) + "\"")
                    .build();

            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName("sparse_content")
                    .vectors(List.of(new EmbeddedText(query)))
                    .topK(topK * 2)
                    .metricType(IndexParam.MetricType.BM25)
                    .expr("collection == \"" + escapeExpr(collection) + "\"")
                    .build();

            SearchResp resp = client.hybridSearch(HybridSearchReq.builder()
                    .collectionName(collectionName)
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(new io.milvus.v2.service.vector.request.ranker.RRFRanker(60))
                    .outFields(List.of("content", "source"))
                    .topK(topK)
                    .build());
            return extractResults(resp);
        } catch (Exception e) {
            log.error("[Milvus] Hybrid search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<Document> searchText(String collection, String query, int topK) {
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            log.warn("searchText() requires an embedding provider. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
            return List.of();
        }
        try {
            Embedding embedding = embeddingProvider.embed(query);
            return searchVector(collection, embedding.vector(), topK);
        } catch (Exception e) {
            log.error("Failed to embed query for Milvus search: {}", e.getMessage(), e);
            return List.of();
        }
    }

    // ==================== 4. Chunk 链表 ====================

    @Override
    public String getPrevChunkId(String chunkId) {
        try {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("id == \"" + escapeExpr(chunkId) + "\"")
                    .outputFields(List.of("prev_chunk_id"))
                    .limit(1L)
                    .build());
            if (!resp.getQueryResults().isEmpty()) {
                Object prevId = resp.getQueryResults().get(0).getEntity().get("prev_chunk_id");
                if (prevId != null && !prevId.toString().isEmpty()) {
                    return prevId.toString();
                }
            }
        } catch (Exception e) {
            log.debug("[Milvus] Failed to get prev_chunk_id for {}: {}", chunkId, e.getMessage());
        }
        return null;
    }

    @Override
    public Document fetchById(String id) {
        try {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("id == \"" + escapeExpr(id) + "\"")
                    .outputFields(List.of("content", "source"))
                    .limit(1L)
                    .build());
            if (!resp.getQueryResults().isEmpty()) {
                Map<String, Object> entity = resp.getQueryResults().get(0).getEntity();
                return new Document(id,
                        entity.get("content") != null ? entity.get("content").toString() : "",
                        entity.get("source") != null ? entity.get("source").toString() : "",
                        1.0, null);
            }
        } catch (Exception e) {
            log.debug("[Milvus] Failed to fetch by id {}: {}", id, e.getMessage());
        }
        return null;
    }

    // ==================== 5. Provider ====================

    @Override
    public String providerName() {
        return "milvus";
    }

    // ==================== 内部工具 ====================

    private List<Document> extractResults(SearchResp resp) {
        List<Document> docs = new ArrayList<>();
        if (resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) return docs;

        for (SearchResp.SearchResult result : resp.getSearchResults().get(0)) {
            Float score = result.getScore();
            if (score != null && score < scoreThreshold) continue;

            Map<String, Object> entity = result.getEntity();
            docs.add(new Document(
                    result.getId() != null ? result.getId().toString() : "",
                    entity != null && entity.get("content") != null ? entity.get("content").toString() : "",
                    entity != null && entity.get("source") != null ? entity.get("source").toString() : "",
                    score != null ? score : 0.0,
                    null));
        }
        log.info("[Milvus] Search returned {} documents", docs.size());
        return docs;
    }

    private String escapeExpr(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private com.google.gson.JsonArray floatArrayToJsonArray(float[] arr) {
        com.google.gson.JsonArray jsonArray = new com.google.gson.JsonArray(arr.length);
        for (float f : arr) jsonArray.add(f);
        return jsonArray;
    }

    private String mapToJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
