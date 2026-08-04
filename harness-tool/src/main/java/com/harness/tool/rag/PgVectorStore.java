package com.harness.tool.rag;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.concurrent.BlockingTaskExecutor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.PgConnectionPool;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * PostgreSQL pgvector 向量存储实现。
 * 实现 VectorStore 通用接口，同时保留 PgVector 特有的 chunk 链表能力。
 *
 * Configured via:
 *   HARNESS_RAG_URL / HARNESS_RAG_PG_URL — JDBC URL
 *   HARNESS_RAG_USER / HARNESS_RAG_PG_USER — DB user
 *   HARNESS_RAG_PASS / HARNESS_RAG_PG_PASS — DB password
 *   HARNESS_RAG_COLLECTION — Filter by collection name
 *   HARNESS_RAG_TOP_K — Max results
 *   HARNESS_RAG_SCORE_THRESHOLD — Min similarity score
 *   HARNESS_MODEL_EMBEDDING_DIM — Embedding dimension
 *   HARNESS_RAG_PG_TABLE — Table name (default: knowledge_documents)
 */
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final String table;
    private final String collection;
    private final int topK;
    private final double scoreThreshold;
    private final int embedDim;
    private final EmbeddingModelProvider embeddingProvider;

    public PgVectorStore() {
        this(null);
    }

    public PgVectorStore(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        this.table = cfg.getString(EnvKey.RAG_PG_TABLE, "knowledge_documents");
        this.collection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        this.topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        this.scoreThreshold = cfg.getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.7);
        this.embedDim = cfg.getInt(EnvKey.MODEL_EMBEDDING_DIM,
                cfg.getInt(EnvKey.RAG_PG_EMBED_DIM, EnvKey.MODEL_EMBEDDING_DIM_DEFAULT));
        this.embeddingProvider = embeddingProvider;
    }

    // ==================== VectorStore 接口实现 ====================

    @Override
    public void upsert(String collection, List<Document> docs) {
        // 转换为内部格式并调用 insertBatchWithLinks
        List<DocumentLinkEntry> entries = new ArrayList<>();
        for (Document doc : docs) {
            entries.add(new DocumentLinkEntry(
                    doc.content(),
                    doc.source(),
                    doc.embedding() != null ? doc.embedding() : new float[0],
                    collection != null ? collection : this.collection,
                    doc.chunkIndex(),
                    doc.metadata()
            ));
        }
        insertBatchWithLinks(entries);
    }

    @Override
    public void delete(String collection) {
        deleteByCollection(collection);
    }

    @Override
    public boolean deleteById(String id) {
        String sql = String.format("DELETE FROM %s WHERE id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(id));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Deleted document {} from knowledge base", id);
                return true;
            }
            log.debug("Document {} not found for deletion", id);
        } catch (SQLException | NumberFormatException e) {
            log.error("Failed to delete document {}: {}", id, e.getMessage(), e);
        }
        return false;
    }

    @Override
    public Document getById(String id) {
        String sql = String.format("SELECT id, content, source FROM %s WHERE id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(id));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new Document(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("content"),
                        rs.getString("source"),
                        1.0,
                        null
                );
            }
        } catch (SQLException | NumberFormatException e) {
            log.debug("Failed to get by id {}: {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public List<Document> listByCollection(String collectionName) {
        String sql = String.format(
                "SELECT id, source, chunk_index, created_at FROM %s WHERE collection = ? ORDER BY id ASC",
                table);
        List<Document> results = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collectionName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new Document(
                        String.valueOf(rs.getLong("id")),
                        null,
                        rs.getString("source"),
                        0,
                        Map.of("chunk_index", rs.getObject("chunk_index") != null ? rs.getInt("chunk_index") : -1,
                                "created_at", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "")
                ));
            }
            log.debug("Listed {} documents in collection '{}'", results.size(), collectionName);
        } catch (SQLException e) {
            log.error("Failed to list collection '{}': {}", collectionName, e.getMessage(), e);
        }
        return results;
    }

    @Override
    public List<String> listCollections() {
        String sql = String.format("SELECT DISTINCT collection FROM %s ORDER BY collection", table);
        List<String> collections = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                collections.add(rs.getString("collection"));
            }
        } catch (SQLException e) {
            log.error("Failed to list collections: {}", e.getMessage(), e);
        }
        return collections;
    }

    @Override
    public List<Document> searchVector(String collection, float[] embedding, int topK) {
        return searchVectorWithEvidence(collection, embedding, topK).documents();
    }

    private SearchResult searchVectorWithEvidence(String collection, float[] embedding, int topK) {
        if (embedding == null || embedding.length == 0) {
            log.warn("Empty embedding, skipping pgvector search");
            return SearchResult.empty();
        }

        String vectorLiteral = toVectorLiteral(embedding);
        String sql = String.format("""
                WITH candidates AS (
                    SELECT id, content, source,
                           1 - (embedding <=> '%s'::vector) AS score
                    FROM %s
                    WHERE collection = ?
                    ORDER BY embedding <=> '%s'::vector
                    LIMIT ?
                ), candidate_stats AS (
                    SELECT COALESCE(MAX(score), 0.0) AS best_observed_score,
                           COUNT(*) AS observed_candidate_count
                    FROM candidates
                )
                SELECT candidates.id, candidates.content, candidates.source, candidates.score,
                       candidate_stats.best_observed_score,
                       candidate_stats.observed_candidate_count
                FROM candidate_stats
                LEFT JOIN candidates ON candidates.score >= ?
                ORDER BY candidates.score DESC NULLS LAST
                """, vectorLiteral, table, vectorLiteral);

        List<Document> results = new ArrayList<>();
        double bestObservedScore = 0.0;
        int observedCandidateCount = 0;
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collection);
            ps.setInt(2, topK);
            ps.setDouble(3, scoreThreshold);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                bestObservedScore = rs.getDouble("best_observed_score");
                observedCandidateCount = rs.getInt("observed_candidate_count");
                if (rs.getObject("id") != null) {
                    results.add(new Document(
                            rs.getString("id"),
                            rs.getString("content"),
                            rs.getString("source"),
                            rs.getDouble("score"),
                            null
                    ));
                }
            }
            log.debug("pgvector search returned {} accepted documents from {} candidates "
                            + "(collection={}, topK={}, bestObservedScore={})",
                    results.size(), observedCandidateCount, collection, topK, bestObservedScore);
        } catch (SQLException e) {
            log.error("pgvector search failed: {}", e.getMessage(), e);
            return SearchResult.empty();
        }
        return new SearchResult(results, bestObservedScore, observedCandidateCount);
    }

    @Override
    public List<Document> searchKeyword(String collection, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();

        EnvConfig cfg = EnvConfig.get();
        String lang = cfg.getString(EnvKey.RAG_LANG,
                cfg.getString(EnvKey.RAG_FULLTEXT_LANG, "english"));

        String sql = "SELECT id, content, source, " +
                "ts_rank_cd(to_tsvector(?, content), plainto_tsquery(?, ?)) AS score " +
                "FROM " + table + " " +
                "WHERE collection = ? " +
                "AND to_tsvector(?, content) @@ plainto_tsquery(?, ?) " +
                "ORDER BY score DESC " +
                "LIMIT ?";

        List<Document> results = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, lang);
            ps.setString(2, lang);
            ps.setString(3, query);
            ps.setString(4, collection);
            ps.setString(5, lang);
            ps.setString(6, lang);
            ps.setString(7, query);
            ps.setInt(8, topK);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double score = rs.getDouble("score");
                    if (score >= 0.1) {
                        results.add(new Document(
                                rs.getString("id"),
                                rs.getString("content"),
                                rs.getString("source"),
                                score,
                                null
                        ));
                    }
                }
            }
            log.debug("pgvector keyword search returned {} documents", results.size());
        } catch (SQLException e) {
            log.warn("[PgVectorStore] Keyword search failed: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public List<Document> searchHybrid(String collection, String query, float[] embedding, int topK) {
        EnvConfig cfg = EnvConfig.get();
        double bm25Weight = cfg.getDouble(EnvKey.RAG_BM25_WEIGHT, 0.3);
        double vectorWeight = 1.0 - bm25Weight;

        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() ->
                searchVector(collection, embedding, topK * 2), BlockingTaskExecutor.shared());
        CompletableFuture<List<Document>> keywordFuture = CompletableFuture.supplyAsync(() ->
                searchKeyword(collection, query, topK * 2), BlockingTaskExecutor.shared());

        CompletableFuture.allOf(vectorFuture, keywordFuture).join();

        List<Document> vectorDocs = vectorFuture.join();
        List<Document> keywordDocs = keywordFuture.join();

        java.util.LinkedHashMap<String, Document> merged = new java.util.LinkedHashMap<>();
        for (Document doc : vectorDocs) {
            merged.put(doc.id(), new Document(doc.id(), doc.content(), doc.source(),
                    doc.score() * vectorWeight, doc.metadata()));
        }
        for (Document doc : keywordDocs) {
            merged.merge(doc.id(), doc, (existing, incoming) -> {
                double combinedScore = existing.score() + incoming.score() * bm25Weight;
                return new Document(existing.id(), existing.content(), existing.source(),
                        combinedScore, existing.metadata());
            });
        }

        List<Document> result = merged.values().stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topK)
                .toList();

        log.debug("Hybrid search: vector={}, keyword={}, merged={}", vectorDocs.size(), keywordDocs.size(), result.size());
        return result;
    }

    @Override
    public String providerName() {
        return "pgvector";
    }

    // ==================== Chunk 链表能力（VectorStore 接口） ====================

    @Override
    public String getPrevChunkId(String chunkId) {
        String sql = String.format("SELECT prev_chunk_id FROM %s WHERE id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(chunkId));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("prev_chunk_id");
            }
        } catch (SQLException | NumberFormatException e) {
            log.debug("Failed to get prev_chunk_id for {}: {}", chunkId, e.getMessage());
        }
        return null;
    }

    @Override
    public Document fetchById(String id) {
        String sql = String.format("SELECT id, content, source FROM %s WHERE id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(id));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new Document(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("content"),
                        rs.getString("source"),
                        1.0,
                        null
                );
            }
        } catch (SQLException | NumberFormatException e) {
            log.debug("Failed to fetch by id {}: {}", id, e.getMessage());
        }
        return null;
    }

    // ==================== PgVector 特有方法 ====================

    @Override
    public List<Document> searchText(String collection, String query, int topK) {
        return searchTextWithEvidence(collection, query, topK).documents();
    }

    @Override
    public SearchResult searchTextWithEvidence(String collection, String query, int topK) {
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            log.warn("searchText() requires an embedding provider. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
            return SearchResult.empty();
        }
        try {
            Embedding embedding = embeddingProvider.embed(query);
            return searchVectorWithEvidence(collection, embedding.vector(), topK);
        } catch (Exception e) {
            log.error("Failed to embed query for RAG retrieval: {}", e.getMessage(), e);
            return SearchResult.empty();
        }
    }

    /**
     * Delete all documents in a collection.
     */
    public int deleteByCollection(String collectionName) {
        String sql = String.format("DELETE FROM %s WHERE collection = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collectionName);
            int deleted = ps.executeUpdate();
            log.info("Deleted {} documents from collection '{}'", deleted, collectionName);
            return deleted;
        } catch (SQLException e) {
            log.error("Failed to delete collection '{}': {}", collectionName, e.getMessage(), e);
        }
        return 0;
    }

    /**
     * List documents as RagDocumentSummary (for backward compatibility).
     */
    public List<RagDocumentSummary> listByCollectionSummary(String collectionName) {
        String sql = String.format(
                "SELECT id, source, chunk_index, created_at FROM %s WHERE collection = ? ORDER BY id ASC",
                table);
        List<RagDocumentSummary> results = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collectionName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("created_at");
                results.add(new RagDocumentSummary(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("source"),
                        rs.getObject("chunk_index") != null ? rs.getInt("chunk_index") : null,
                        ts != null ? ts.toInstant() : null
                ));
            }
            log.debug("Listed {} documents in collection '{}'", results.size(), collectionName);
        } catch (SQLException e) {
            log.error("Failed to list collection '{}': {}", collectionName, e.getMessage(), e);
        }
        return results;
    }

    /**
     * Insert multiple documents with chunk linking (prev_chunk_id, next_chunk_id).
     * Uses a two-step approach: insert all chunks, then update links in a batch.
     */
    public List<Long> insertBatchWithLinks(List<DocumentLinkEntry> entries) {
        String insertSql = String.format("""
                INSERT INTO %s (collection, source, content, embedding, chunk_index, metadata)
                VALUES (?, ?, ?, ?::vector, ?, ?::jsonb)
                RETURNING id
                """, table);

        String updateLinkSql = String.format("""
                UPDATE %s SET prev_chunk_id = ?, next_chunk_id = ? WHERE id = ?
                """, table);

        List<Long> ids = new ArrayList<>();
        Connection conn = null;
        try {
            conn = PgConnectionPool.getConnection();
            conn.setAutoCommit(false);

            // Step 1: Insert all chunks and collect IDs
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (DocumentLinkEntry entry : entries) {
                    ps.setString(1, entry.collection() != null ? entry.collection() : collection);
                    ps.setString(2, entry.source());
                    ps.setString(3, entry.content());
                    ps.setString(4, toVectorLiteral(entry.embedding()));
                    ps.setInt(5, entry.chunkIndex());
                    ps.setString(6, entry.metadata() != null ? mapToJson(entry.metadata()) : "{}");
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
            }

            // Step 2: Update prev/next links
            try (PreparedStatement ps = conn.prepareStatement(updateLinkSql)) {
                for (int i = 0; i < ids.size(); i++) {
                    long prevId = i > 0 ? ids.get(i - 1) : 0;
                    long nextId = i < ids.size() - 1 ? ids.get(i + 1) : 0;
                    ps.setString(1, prevId > 0 ? String.valueOf(prevId) : null);
                    ps.setString(2, nextId > 0 ? String.valueOf(nextId) : null);
                    ps.setLong(3, ids.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            conn.commit();
            log.info("Inserted {} linked documents into pgvector", entries.size());

        } catch (SQLException e) {
            log.error("pgvector linked batch insert failed, rolling back: {}", e.getMessage(), e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    log.error("Rollback failed: {}", rollbackEx.getMessage(), rollbackEx);
                }
            }
            ids.clear();
            throw new RuntimeException("Knowledge base batch insert failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException closeEx) {
                    log.debug("Failed to close connection: {}", closeEx.getMessage());
                }
            }
        }

        return ids;
    }

    // ==================== 内部工具方法 ====================

    private String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String mapToJson(Map<String, Object> map) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ==================== 记录类型 ====================

    public record DocumentLinkEntry(
            String content,
            String source,
            float[] embedding,
            String collection,
            int chunkIndex,
            Map<String, Object> metadata
    ) {}

    public record RagDocumentSummary(
            String id,
            String source,
            Integer chunkIndex,
            java.time.Instant createdAt
    ) {}
}
