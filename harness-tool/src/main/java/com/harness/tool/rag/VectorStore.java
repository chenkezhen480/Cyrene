package com.harness.tool.rag;

import com.harness.core.model.PageResponse;
import com.harness.tool.knowledge.KnowledgeChunkSummary;

import java.util.List;
import java.util.Map;

/**
 * 向量存储通用接口。
 * 所有向量数据库实现（PgVector、Milvus 等）都实现此接口。
 * 不支持的方法抛出 UnsupportedOperationException。
 */
public interface VectorStore {

    // ==================== 1. 基础管理 ====================

    /**
     * 批量写入文档（upsert 语义）。
     * Document 需包含 embedding 字段。
     */
    void upsert(String collection, List<Document> docs);

    /**
     * 删除整个集合的所有文档。
     */
    void delete(String collection);

    /**
     * 按 ID 删除单个文档。
     *
     * @return true 如果文档存在并被删除
     */
    boolean deleteById(String collection, String id);

    // ==================== 2. 查询能力 ====================

    /**
     * 按 ID 获取单个文档。
     */
    Document getById(String collection, String id);

    /**
     * Atomically replace one chunk's content and embedding inside its collection.
     * Implementations must fail when the scoped chunk does not exist.
     */
    void updateContent(String collection, String id, String content, float[] embedding);

    /**
     * Cursor-paginated management projection. Implementations must use a stable
     * primary-key order, fetch limit + 1 rows, and bind cursors to the query scope.
     */
    PageResponse<KnowledgeChunkSummary> listKnowledgeChunks(
            String collection,
            String fileName,
            int limit,
            String cursor
    );

    /** Cursor-paginated logical collection names in stable lexical order. */
    PageResponse<String> listCollections(int limit, String cursor);

    // ==================== 3. 检索能力 ====================

    /**
     * 向量相似度检索。
     */
    List<Document> searchVector(String collection, float[] embedding, int topK);

    /**
     * 关键词/BM25 全文检索。
     */
    List<Document> searchKeyword(String collection, String query, int topK);

    /**
     * 混合检索（向量 + 关键词加权融合）。
     * 天然支持的库（如 Milvus）直接实现；PG 在内部并发调用两路后融合。
     */
    List<Document> searchHybrid(String collection, String query, float[] embedding, int topK);

    /**
     * 文本检索（内部自动 embed query）。
     * 需要 embeddingProvider 支持，默认返回空。
     */
    default List<Document> searchText(String collection, String query, int topK) {
        return List.of();
    }

    /**
     * 文本检索，同时返回硬阈值过滤前的候选分数证据。
     * 低于硬阈值的文档不得出现在 {@link SearchResult#documents()} 中；
     * {@code bestObservedScore} 仅用于判断是否值得进行一次隐式查询改写。
     */
    default SearchResult searchTextWithEvidence(String collection, String query, int topK) {
        return SearchResult.fromAccepted(searchText(collection, query, topK));
    }

    // ==================== 4. 显式文档上下文 ====================

    /**
     * Read one bounded window from a stable document anchor.
     * Results must be ordered by chunkIndex and remain inside collection + documentId.
     */
    List<Document> readDocumentWindow(
            String collection,
            String documentId,
            int anchorChunkIndex,
            int before,
            int after
    );

    // ==================== 5. Provider 名称 ====================

    String providerName();

    /**
     * 检索结果及过滤前的有限统计证据。不会携带被硬阈值拒绝的文档正文。
     */
    record SearchResult(
            List<Document> documents,
            double bestObservedScore,
            int observedCandidateCount
    ) {
        public SearchResult {
            documents = documents != null ? List.copyOf(documents) : List.of();
            if (observedCandidateCount < documents.size()) {
                throw new IllegalArgumentException("observedCandidateCount cannot be smaller than accepted documents");
            }
        }

        public static SearchResult fromAccepted(List<Document> documents) {
            List<Document> accepted = documents != null ? List.copyOf(documents) : List.of();
            double bestScore = accepted.stream().mapToDouble(Document::score).max().orElse(0.0);
            return new SearchResult(accepted, bestScore, accepted.size());
        }

        public static SearchResult empty() {
            return new SearchResult(List.of(), 0.0, 0);
        }
    }

    /**
     * 通用文档记录。
     * embedding 和 chunkIndex 用于写入，查询时可为 null/-1。
     */
    record Document(
            String id,
            String content,
            String source,
            double score,
            Map<String, Object> metadata,
            float[] embedding,
            int chunkIndex
    ) {
        /** 查询结果用的便捷构造（无 embedding、无 chunkIndex） */
        public Document(String id, String content, String source, double score, Map<String, Object> metadata) {
            this(id, content, source, score, metadata, null, -1);
        }
    }
}
