package com.harness.preprocess.rag;

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
    boolean deleteById(String id);

    // ==================== 2. 查询能力 ====================

    /**
     * 按 ID 获取单个文档。
     */
    Document getById(String id);

    /**
     * 列出某个集合下的所有文档（不含向量字段，用于管理展示）。
     */
    List<Document> listByCollection(String collection);

    /**
     * 列出所有集合名称。
     */
    List<String> listCollections();

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

    // ==================== 4. Chunk 链表能力（语义回溯） ====================

    /**
     * 获取指定 chunk 的前一个 chunk ID。
     * 用于 SemanticContextRetriever 语义完整性回溯。
     * 默认返回 null（不支持链表的实现）。
     */
    default String getPrevChunkId(String chunkId) {
        return null;
    }

    /**
     * 按 ID 获取完整文档（含 content），用于回溯拼接。
     * 默认返回 null。
     */
    default Document fetchById(String id) {
        return null;
    }

    // ==================== 5. Provider 名称 ====================

    String providerName();

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
