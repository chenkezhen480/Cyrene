package com.harness.tool.rag;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.PageResponse;
import com.harness.tool.knowledge.KnowledgeChunkSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.response.QueryResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Milvus 向量存储实现（v2 API）。
 * 纯检索层，不负责 collection 初始化（由 MilvusCollectionInitializer 处理）。
 *
 * 支持：向量检索、BM25 全文检索、混合检索（RRF 融合）和显式文档窗口。
 */
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

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
        this.collectionName = cfg.getString(EnvKey.RAG_COLLECTION, "knowledge_documents"); // 物理集合名 = env 变量
        this.logicalCollection = this.collectionName;
        this.topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        this.scoreThreshold = cfg.getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.7);
        this.bm25Weight = cfg.getDouble(EnvKey.RAG_BM25_WEIGHT, 0.3);
        this.embeddingProvider = embeddingProvider;
        log.info("[Milvus] collection='{}', topK={}, scoreThreshold={}, bm25Weight={}", collectionName, topK, scoreThreshold, bm25Weight);
        this.client = MilvusConnectionPool.getClient();
    }

    MilvusVectorStore(
            MilvusClientV2 client,
            String collectionName,
            String logicalCollection,
            EmbeddingModelProvider embeddingProvider
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.collectionName = Objects.requireNonNull(collectionName, "collectionName");
        this.logicalCollection = Objects.requireNonNull(logicalCollection, "logicalCollection");
        this.topK = 5;
        this.scoreThreshold = 0.7;
        this.bm25Weight = 0.3;
        this.embeddingProvider = embeddingProvider;
    }

    // ==================== 1. 基础管理 ====================

    @Override
    public void upsert(String collection, List<Document> docs) {
        String coll = requireCollection(collection != null ? collection : logicalCollection);
        validateUpsertIdScopes(coll, docs);

        // Preserve supplied IDs for true upsert semantics; generate IDs only for new chunks.
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            String id = docs.get(i).id();
            ids.add(id == null || id.isBlank()
                    ? UUID.randomUUID().toString().replace("-", "")
                    : id);
        }

        // 一次性插入同一文档的所有 chunks。
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
            row.add("metadata", com.google.gson.JsonParser.parseString(
                    doc.metadata() != null ? mapToJson(doc.metadata()) : "{}"));
            rows.add(row);
        }

        client.upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build());

        log.info("[Milvus] Upserted {} documents into logical collection '{}'", docs.size(), coll);
    }

    private void validateUpsertIdScopes(String collection, List<Document> docs) {
        for (Document document : docs) {
            if (document.id() == null || document.id().isBlank()) {
                continue;
            }
            QueryResp response = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("id == {idValue}")
                    .filterTemplateValues(Map.of("idValue", document.id()))
                    .outputFields(List.of("collection"))
                    .limit(1L)
                    .build());
            if (response.getQueryResults().isEmpty()) {
                continue;
            }
            Object storedCollection = response.getQueryResults().get(0)
                    .getEntity().get("collection");
            if (storedCollection != null && !collection.equals(storedCollection.toString())) {
                throw new IllegalArgumentException(
                        "Knowledge chunk id belongs to another logical collection: "
                                + document.id());
            }
        }
    }

    @Override
    public void delete(String collection) {
        client.delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter("collection == {collectionValue}")
                .filterTemplateValues(Map.of(
                        "collectionValue", requireCollection(collection)))
                .build());
        log.info("[Milvus] Deleted logical collection '{}' from physical collection '{}'",
                collection, collectionName);
    }

    @Override
    public boolean deleteById(String collection, String id) {
        try {
            long deleted = client.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter("collection == {collectionValue} and id == {idValue}")
                    .filterTemplateValues(Map.of(
                            "collectionValue", requireCollection(collection),
                            "idValue", requireId(id)))
                    .build()).getDeleteCnt();
            if (deleted > 0) {
                log.info("[Milvus] Deleted document {} from logical collection '{}'", id, collection);
                return true;
            }
            return false;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to delete Milvus knowledge chunk " + id, e);
        }
    }

    // ==================== 2. 查询能力 ====================

    @Override
    public Document getById(String collection, String id) {
        try {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("collection == {collectionValue} and id == {idValue}")
                    .filterTemplateValues(Map.of(
                            "collectionValue", requireCollection(collection),
                            "idValue", requireId(id)))
                    .outputFields(List.of("content", "source", "chunk_index", "metadata"))
                    .limit(1L)
                    .build());
            if (!resp.getQueryResults().isEmpty()) {
                Map<String, Object> entity = resp.getQueryResults().get(0).getEntity();
                return new Document(id,
                        entity.get("content") != null ? entity.get("content").toString() : "",
                        entity.get("source") != null ? entity.get("source").toString() : "",
                        1.0,
                        parseMetadata(entity.get("metadata")),
                        null,
                        integerValue(entity.get("chunk_index")));
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to get Milvus knowledge chunk " + id, e);
        }
        return null;
    }

    @Override
    public void updateContent(String collection, String id, String content, float[] embedding) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Knowledge chunk content is required");
        }
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Knowledge chunk embedding is required");
        }
        Document existing = getById(collection, id);
        if (existing == null) {
            throw new IllegalArgumentException(
                    "Knowledge chunk does not exist in collection: " + id);
        }
        upsert(collection, List.of(new Document(
                id,
                content,
                existing.source(),
                existing.score(),
                existing.metadata(),
                embedding,
                existing.chunkIndex())));
    }

    @Override
    public PageResponse<KnowledgeChunkSummary> listKnowledgeChunks(
            String collection,
            String fileName,
            int limit,
            String cursor
    ) {
        validateManagementQuery(collection, limit);
        String normalizedFileName = KnowledgeChunkCursorCodec.normalizeFileName(fileName);
        String lastId = KnowledgeChunkCursorCodec.decodeLastId(
                cursor, collection, normalizedFileName);

        List<String> filterClauses = new ArrayList<>();
        filterClauses.add(equalsExpression("collection", collection));
        if (!normalizedFileName.isBlank()) {
            filterClauses.add(equalsExpression("source", normalizedFileName));
        }
        if (lastId != null) {
            filterClauses.add("id > " + stringLiteral(lastId));
        }

        QueryIterator iterator = null;
        try {
            // Milvus Query does not guarantee row order. QueryIterator advances by the
            // primary key and is therefore the stable keyset source for management pages.
            iterator = client.queryIterator(QueryIteratorReq.builder()
                    .collectionName(collectionName)
                    .expr(String.join(" and ", filterClauses))
                    .outputFields(List.of("id", "source", "chunk_index", "metadata"))
                    .batchSize((long) limit + 1L)
                    .limit((long) limit + 1L)
                    .build());

            List<KnowledgeChunkSummary> fetched = new ArrayList<>();
            for (QueryResultsWrapper.RowRecord row : iterator.next()) {
                Map<String, Object> entity = row.getFieldValues();
                Map<String, Object> metadata = parseMetadata(entity.get("metadata"));
                fetched.add(new KnowledgeChunkSummary(
                        entity.get("id") != null ? entity.get("id").toString() : "",
                        entity.get("source") != null ? entity.get("source").toString() : "",
                        integerValue(entity.get("chunk_index")),
                        stringMetadata(metadata, "document_id"),
                        stringListMetadata(metadata, "heading_path")));
            }
            fetched.sort(Comparator.comparing(KnowledgeChunkSummary::id));
            return PageResponse.fromFetched(
                    fetched,
                    limit,
                    item -> KnowledgeChunkCursorCodec.encode(
                            collection, normalizedFileName, item.id()));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to list Milvus knowledge chunks for collection '" + collection + "'", e);
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }
    }

    @Override
    public PageResponse<String> listCollections(int limit, String cursor) {
        validateCollectionPageLimit(limit);
        String lastCollection = KnowledgeChunkCursorCodec.decodeLastCollection(cursor);
        QueryIterator iterator = null;
        try {
            iterator = client.queryIterator(QueryIteratorReq.builder()
                    .collectionName(collectionName)
                    .expr(lastCollection == null
                            ? "id != \"\""
                            : "collection > " + stringLiteral(lastCollection))
                    .outputFields(List.of("collection"))
                    .batchSize(512L)
                    .build());
            Set<String> collections = new TreeSet<>();
            while (true) {
                List<QueryResultsWrapper.RowRecord> rows = iterator.next();
                if (rows.isEmpty()) {
                    break;
                }
                for (QueryResultsWrapper.RowRecord row : rows) {
                    Object value = row.getFieldValues().get("collection");
                    if (value != null && !value.toString().isBlank()) {
                        collections.add(value.toString());
                    }
                }
            }
            return PageResponse.fromFetched(
                    collections.stream().limit((long) limit + 1L).toList(),
                    limit,
                    KnowledgeChunkCursorCodec::encodeCollection);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to list Milvus logical collections", exception);
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }
    }

    // ==================== 3. 检索能力 ====================

    @Override
    public List<Document> searchVector(String collection, float[] embedding, int topK) {
        return searchVectorWithEvidence(collection, embedding, topK).documents();
    }

    private SearchResult searchVectorWithEvidence(String collection, float[] embedding, int topK) {
        if (embedding == null || embedding.length == 0) {
            log.warn("Empty embedding, skipping Milvus vector search");
            return SearchResult.empty();
        }
        try {
            SearchResp resp = client.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField("embedding")
                    .data(List.of(new FloatVec(toFloatList(embedding))))
                    .topK(topK)
                    .filter(collectionFilter(collection))
                    .metricType(IndexParam.MetricType.COSINE)
                    .outputFields(List.of("content", "source", "chunk_index", "metadata"))
                    .build());
            return extractSearchResult(resp);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus vector search failed", e);
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
                    .filter(collectionFilter(collection))
                    .metricType(IndexParam.MetricType.BM25)
                    .outputFields(List.of("content", "source", "chunk_index", "metadata"))
                    .build());
            return extractResults(resp);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus BM25 search failed", e);
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
                    .expr(collectionFilter(collection))
                    .metricType(IndexParam.MetricType.COSINE)
                    .build();

            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName("sparse_content")
                    .vectors(List.of(new EmbeddedText(query)))
                    .topK(topK * 2)
                    .expr(collectionFilter(collection))
                    .metricType(IndexParam.MetricType.BM25)
                    .build();

            SearchResp resp = client.hybridSearch(HybridSearchReq.builder()
                    .collectionName(collectionName)
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(new io.milvus.v2.service.vector.request.ranker.RRFRanker(60))
                    .outFields(List.of("content", "source", "chunk_index", "metadata"))
                    .topK(topK)
                    .build());
            return extractResults(resp);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus hybrid search failed", e);
        }
    }

    @Override
    public List<Document> searchText(String collection, String query, int topK) {
        return searchTextWithEvidence(collection, query, topK).documents();
    }

    @Override
    public SearchResult searchTextWithEvidence(String collection, String query, int topK) {
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            throw new IllegalStateException(
                    "searchText() requires an embedding provider. Set "
                            + com.harness.core.modelconfig.ModelConfigKey.EMBEDDING_PROVIDER
                            + " in model.conf.");
        }
        Embedding embedding;
        try {
            embedding = embeddingProvider.embed(query);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to embed query for Milvus search", e);
        }
        return searchVectorWithEvidence(collection, embedding.vector(), topK);
    }

    // ==================== 4. Explicit document context ====================

    @Override
    public List<Document> readDocumentWindow(
            String collection,
            String documentId,
            int anchorChunkIndex,
            int before,
            int after
    ) {
        validateWindowArguments(collection, documentId, anchorChunkIndex, before, after);
        int startIndex = Math.max(0, anchorChunkIndex - before);
        int endIndex = Math.addExact(anchorChunkIndex, after);
        String filter = collectionFilter(collection)
                + " and metadata[\"document_id\"] == \"" + escapeExpr(documentId) + "\""
                + " and chunk_index >= " + startIndex
                + " and chunk_index <= " + endIndex;
        try {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .outputFields(List.of("id", "content", "source", "chunk_index", "metadata"))
                    .limit((long) before + after + 1L)
                    .build());
            List<Document> documents = new ArrayList<>();
            for (QueryResp.QueryResult result : resp.getQueryResults()) {
                Map<String, Object> entity = result.getEntity();
                documents.add(new Document(
                        entity.get("id") != null ? entity.get("id").toString() : "",
                        entity.get("content") != null ? entity.get("content").toString() : "",
                        entity.get("source") != null ? entity.get("source").toString() : "",
                        0.0,
                        parseMetadata(entity.get("metadata")),
                        null,
                        integerValue(entity.get("chunk_index"))));
            }
            return documents.stream()
                    .sorted(Comparator.comparingInt(Document::chunkIndex)
                            .thenComparing(Document::id))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Milvus document context", e);
        }
    }

    // ==================== 5. Provider ====================

    @Override
    public String providerName() {
        return "milvus";
    }

    // ==================== 内部工具 ====================

    private List<Document> extractResults(SearchResp resp) {
        return extractSearchResult(resp).documents();
    }

    private SearchResult extractSearchResult(SearchResp resp) {
        List<Document> docs = new ArrayList<>();
        if (resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return SearchResult.empty();
        }

        List<SearchResp.SearchResult> candidates = resp.getSearchResults().get(0);
        double bestObservedScore = 0.0;
        for (SearchResp.SearchResult result : candidates) {
            Float score = result.getScore();
            if (score != null) {
                bestObservedScore = Math.max(bestObservedScore, score);
            }
            if (score != null && score < scoreThreshold) continue;

            Map<String, Object> entity = result.getEntity();
            docs.add(new Document(
                    result.getId() != null ? result.getId().toString() : "",
                    entity != null && entity.get("content") != null ? entity.get("content").toString() : "",
                    entity != null && entity.get("source") != null ? entity.get("source").toString() : "",
                    score != null ? score : 0.0,
                    parseMetadata(entity != null ? entity.get("metadata") : null),
                    null,
                    integerValue(entity != null ? entity.get("chunk_index") : null)));
        }
        log.debug("[Milvus] Search on '{}' returned {} accepted documents from {} candidates "
                        + "(bestObservedScore={})",
                collectionName, docs.size(), candidates.size(), bestObservedScore);
        return new SearchResult(docs, bestObservedScore, candidates.size());
    }

    private static String escapeExpr(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stringLiteral(String value) {
        return "\"" + escapeExpr(value) + "\"";
    }

    private static String equalsExpression(String field, String value) {
        return field + " == " + stringLiteral(value);
    }

    private static String requireCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection is required");
        }
        return collection;
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("knowledge chunk id is required");
        }
        return id;
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
            return JSON_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize knowledge document metadata", e);
        }
    }

    private static Map<String, Object> parseMetadata(Object value) {
        if (value == null || value.toString().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = JSON_MAPPER.readValue(value.toString(), METADATA_TYPE);
            return metadata == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse knowledge document metadata", e);
        }
    }

    private static int integerValue(Object value) {
        if (value == null) {
            return -1;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Knowledge chunk_index must be an integer", e);
        }
    }

    private static String stringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString();
    }

    private static List<String> stringListMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private static void validateManagementQuery(String collection, int limit) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection is required");
        }
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private static void validateCollectionPageLimit(int limit) {
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private String collectionFilter(String collection) {
        String effectiveCollection = collection == null || collection.isBlank()
                ? logicalCollection
                : collection;
        return equalsExpression("collection", requireCollection(effectiveCollection));
    }

    private static void validateWindowArguments(
            String collection,
            String documentId,
            int anchorChunkIndex,
            int before,
            int after
    ) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection is required");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (anchorChunkIndex < 0 || before < 0 || after < 0) {
            throw new IllegalArgumentException("chunk indexes and window sizes cannot be negative");
        }
    }
}
