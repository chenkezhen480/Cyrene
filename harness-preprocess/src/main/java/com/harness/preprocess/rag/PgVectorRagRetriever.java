package com.harness.preprocess.rag;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.env.PgConnectionPool;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL pgvector RAG retriever.
 * Performs cosine similarity search against a vector column.
 *
 * Configured via:
 *   HARNESS_RAG_PG_URL      - JDBC URL (default: jdbc:postgresql://localhost:5432/agent)
 *   HARNESS_RAG_PG_USER     - DB user (default: postgres)
 *   HARNESS_RAG_PG_PASS     - DB password
 *   HARNESS_RAG_PG_TABLE    - Table name (default: knowledge_documents)
 *   HARNESS_RAG_PG_EMBED_DIM - Embedding dimension (default: 1536)
 *   HARNESS_RAG_COLLECTION   - Filter by collection name
 *   HARNESS_RAG_TOP_K        - Max results
 *   HARNESS_RAG_SCORE_THRESHOLD - Min similarity score
 *
 * Schema: see resources/schema-pgvector.sql
 */
public class PgVectorRagRetriever {

    private static final Logger log = LoggerFactory.getLogger(PgVectorRagRetriever.class);

    private final String table;
    private final String collection;
    private final int topK;
    private final double scoreThreshold;
    private final int embedDim;
    private final EmbeddingModelProvider embeddingProvider;

    public PgVectorRagRetriever() {
        this(null);
    }

    public PgVectorRagRetriever(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        this.table = cfg.getString(EnvKey.RAG_PG_TABLE, "knowledge_documents");
        this.collection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        this.topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        this.scoreThreshold = cfg.getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.7);
        this.embedDim = cfg.getInt(EnvKey.RAG_PG_EMBED_DIM, 1536);
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Retrieve documents by vector similarity.
     *
     * @param queryEmbedding the query vector (must match embedDim)
     * @return matched documents sorted by similarity descending
     */
    public List<RagRetriever.RagDocument> retrieveByVector(float[] queryEmbedding) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            log.warn("Empty query embedding, skipping pgvector search");
            return Collections.emptyList();
        }

        String vectorLiteral = toVectorLiteral(queryEmbedding);
        String sql = String.format("""
                SELECT id, content, source,
                       1 - (embedding <=> '%s'::vector) AS score
                FROM %s
                WHERE collection = ?
                  AND 1 - (embedding <=> '%s'::vector) >= ?
                ORDER BY embedding <=> '%s'::vector
                LIMIT ?
                """, vectorLiteral, table, vectorLiteral, vectorLiteral);

        List<RagRetriever.RagDocument> results = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, collection);
            ps.setDouble(2, scoreThreshold);
            ps.setInt(3, topK);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new RagRetriever.RagDocument(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getString("source"),
                        rs.getDouble("score")
                ));
            }

            log.info("pgvector search returned {} documents (collection={}, topK={})", results.size(), collection, topK);

        } catch (SQLException e) {
            log.error("pgvector search failed: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * Retrieve by text query (requires external embedding service).
     * This is a convenience method - the caller should embed the query first
     * and call retrieveByVector() directly for production use.
     *
     * @param queryText raw text query
     * @return matched documents
     */
    public List<RagRetriever.RagDocument> retrieveByText(String queryText) {
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            log.warn("retrieveByText() requires an embedding provider. Set HARNESS_MODEL_EMBEDDING_PROVIDER.");
            return Collections.emptyList();
        }
        try {
            Embedding embedding = embeddingProvider.embed(queryText);
            return retrieveByVector(embedding.vector());
        } catch (Exception e) {
            log.error("Failed to embed query for RAG retrieval: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Insert a document with its embedding into the knowledge base.
     */
    public void insert(String content, String source, float[] embedding, String collectionName) {
        String vectorLiteral = toVectorLiteral(embedding);
        String sql = String.format("""
                INSERT INTO %s (collection, source, content, embedding)
                VALUES (?, ?, ?, '%s'::vector)
                """, table, vectorLiteral);

        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, collectionName != null ? collectionName : collection);
            ps.setString(2, source);
            ps.setString(3, content);
            ps.executeUpdate();

        } catch (SQLException e) {
            log.error("pgvector insert failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Insert multiple documents in batch.
     */
    public void insertBatch(List<DocumentEntry> entries) {
        String sql = String.format("""
                INSERT INTO %s (collection, source, content, embedding)
                VALUES (?, ?, ?, ?::vector)
                """, table);

        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (DocumentEntry entry : entries) {
                ps.setString(1, entry.collection() != null ? entry.collection() : collection);
                ps.setString(2, entry.source());
                ps.setString(3, entry.content());
                ps.setString(4, toVectorLiteral(entry.embedding()));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
            log.info("Inserted {} documents into pgvector", entries.size());

        } catch (SQLException e) {
            log.error("pgvector batch insert failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Retrieve a single document by its ID.
     */
    public RagRetriever.RagDocument retrieveById(String id) {
        String sql = String.format("SELECT id, content, source FROM %s WHERE id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(id));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new RagRetriever.RagDocument(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("content"),
                        rs.getString("source"),
                        1.0
                );
            }
        } catch (SQLException | NumberFormatException e) {
            log.debug("Failed to retrieve by id {}: {}", id, e.getMessage());
        }
        return null;
    }

    /**
     * Get the prev_chunk_id for a given chunk ID.
     */
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

    /**
     * Delete all documents in a collection.
     * Returns the number of deleted documents.
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
     * Delete a specific document by its ID.
     *
     * @return true if the document was found and deleted
     */
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

    /**
     * List all documents in a collection (id, source, chunk_index, created_at).
     * Does not return embeddings or full content for efficiency.
     */
    public List<RagDocumentSummary> listByCollection(String collectionName) {
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
            log.info("Listed {} documents in collection '{}'", results.size(), collectionName);
        } catch (SQLException e) {
            log.error("Failed to list collection '{}': {}", collectionName, e.getMessage(), e);
        }
        return results;
    }

    /**
     * Summary of a document for listing purposes (no embedding/full content).
     */
    public record RagDocumentSummary(
            String id,
            String source,
            Integer chunkIndex,
            java.time.Instant createdAt
    ) {}

    private String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    public record DocumentEntry(
            String content,
            String source,
            float[] embedding,
            String collection
    ) {}

    public record DocumentLinkEntry(
            String content,
            String source,
            float[] embedding,
            String collection,
            int chunkIndex,
            Map<String, Object> metadata
    ) {}

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

    private String mapToJson(Map<String, Object> map) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
