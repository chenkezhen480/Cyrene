package com.harness.tool.rag;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.concurrent.BlockingTaskExecutor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.PgConnectionPool;
import com.harness.core.model.PageResponse;
import com.harness.tool.knowledge.KnowledgeChunkSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 实现 VectorStore 通用接口。
 *
 * Configured via:
 *   HARNESS_RAG_URL / HARNESS_RAG_PG_URL — JDBC URL
 *   HARNESS_RAG_USER / HARNESS_RAG_PG_USER — DB user
 *   HARNESS_RAG_PASS / HARNESS_RAG_PG_PASS — DB password
 *   HARNESS_RAG_COLLECTION — Filter by collection name
 *   HARNESS_RAG_TOP_K — Max results
 *   HARNESS_RAG_SCORE_THRESHOLD — Min similarity score
 *   embedding.dimension in model.conf — Embedding dimension
 *   HARNESS_RAG_PG_TABLE — Table name (default: knowledge_documents)
 */
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

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
        this.embeddingProvider = embeddingProvider;
        int providerDimension = embeddingProvider != null ? embeddingProvider.dimension() : 0;
        this.embedDim = providerDimension > 0
                ? providerDimension
                : 1024;
    }

    // ==================== VectorStore 接口实现 ====================

    @Override
    public void upsert(String collection, List<Document> docs) {
        List<DocumentEntry> entries = new ArrayList<>();
        for (Document doc : docs) {
            entries.add(new DocumentEntry(
                    parseOptionalId(doc.id()),
                    doc.content(),
                    doc.source(),
                    doc.embedding() != null ? doc.embedding() : new float[0],
                    collection != null ? collection : this.collection,
                    doc.chunkIndex(),
                    doc.metadata()
            ));
        }
        insertBatch(entries);
    }

    @Override
    public void delete(String collection) {
        deleteByCollection(collection);
    }

    @Override
    public boolean deleteById(String collection, String id) {
        long numericId = parseRequiredId(id);
        String sql = String.format("DELETE FROM %s WHERE collection = ? AND id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requireCollection(collection));
            ps.setLong(2, numericId);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Deleted document {} from knowledge base", id);
                return true;
            }
            log.debug("Document {} not found for deletion", id);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete pgvector knowledge chunk " + id, e);
        }
        return false;
    }

    @Override
    public Document getById(String collection, String id) {
        long numericId = parseRequiredId(id);
        String sql = String.format(
                "SELECT id, content, source, chunk_index, metadata FROM %s "
                        + "WHERE collection = ? AND id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requireCollection(collection));
            ps.setLong(2, numericId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new Document(
                        String.valueOf(rs.getLong("id")),
                        rs.getString("content"),
                        rs.getString("source"),
                        1.0,
                        parseMetadata(rs.getString("metadata")),
                        null,
                        nullableChunkIndex(rs)
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read pgvector knowledge chunk " + id, e);
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
        long numericId = parseRequiredId(id);
        String sql = String.format(
                "UPDATE %s SET content = ?, embedding = ?::vector "
                        + "WHERE collection = ? AND id = ?", table);
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, content);
            ps.setString(2, toVectorLiteral(embedding));
            ps.setString(3, requireCollection(collection));
            ps.setLong(4, numericId);
            if (ps.executeUpdate() != 1) {
                throw new IllegalArgumentException(
                        "Knowledge chunk does not exist in collection: " + id);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update pgvector knowledge chunk " + id, e);
        }
    }

    @Override
    public PageResponse<KnowledgeChunkSummary> listKnowledgeChunks(
            String collectionName,
            String fileName,
            int limit,
            String cursor
    ) {
        validateManagementQuery(collectionName, limit);
        String normalizedFileName = KnowledgeChunkCursorCodec.normalizeFileName(fileName);
        String lastId = KnowledgeChunkCursorCodec.decodeLastId(
                cursor, collectionName, normalizedFileName);
        Long lastNumericId = parseCursorId(lastId);

        StringBuilder sql = new StringBuilder(String.format(
                "SELECT id, source, chunk_index, metadata FROM %s WHERE collection = ?",
                table));
        if (!normalizedFileName.isBlank()) {
            sql.append(" AND source = ?");
        }
        if (lastNumericId != null) {
            sql.append(" AND id > ?");
        }
        sql.append(" ORDER BY id ASC LIMIT ?");

        List<KnowledgeChunkSummary> fetched = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int parameterIndex = 1;
            ps.setString(parameterIndex++, collectionName);
            if (!normalizedFileName.isBlank()) {
                ps.setString(parameterIndex++, normalizedFileName);
            }
            if (lastNumericId != null) {
                ps.setLong(parameterIndex++, lastNumericId);
            }
            ps.setInt(parameterIndex, limit + 1);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
                    fetched.add(toKnowledgeChunkSummary(
                            String.valueOf(rs.getLong("id")),
                            rs.getString("source"),
                            nullableChunkIndex(rs),
                            metadata));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to list knowledge chunks for collection '" + collectionName + "'", e);
        }

        return PageResponse.fromFetched(
                fetched,
                limit,
                item -> KnowledgeChunkCursorCodec.encode(
                        collectionName, normalizedFileName, item.id()));
    }

    @Override
    public PageResponse<String> listCollections(int limit, String cursor) {
        validateCollectionPageLimit(limit);
        String lastCollection = KnowledgeChunkCursorCodec.decodeLastCollection(cursor);
        String sql = lastCollection == null
                ? String.format(
                        "SELECT DISTINCT collection FROM %s ORDER BY collection LIMIT ?", table)
                : String.format(
                        "SELECT DISTINCT collection FROM %s WHERE collection > ? "
                                + "ORDER BY collection LIMIT ?", table);
        List<String> collections = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int parameterIndex = 1;
            if (lastCollection != null) {
                ps.setString(parameterIndex++, lastCollection);
            }
            ps.setInt(parameterIndex, limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    collections.add(rs.getString("collection"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list knowledge collections", e);
        }
        return PageResponse.fromFetched(
                collections, limit, KnowledgeChunkCursorCodec::encodeCollection);
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
                    SELECT id, content, source, chunk_index, metadata,
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
                SELECT candidates.id, candidates.content, candidates.source, candidates.chunk_index,
                       candidates.metadata, candidates.score,
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
                            parseMetadata(rs.getString("metadata")),
                            null,
                            nullableChunkIndex(rs)
                    ));
                }
            }
            log.debug("pgvector search returned {} accepted documents from {} candidates "
                            + "(collection={}, topK={}, bestObservedScore={})",
                    results.size(), observedCandidateCount, collection, topK, bestObservedScore);
        } catch (SQLException e) {
            throw new IllegalStateException("pgvector vector search failed", e);
        }
        return new SearchResult(results, bestObservedScore, observedCandidateCount);
    }

    @Override
    public List<Document> searchKeyword(String collection, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();

        EnvConfig cfg = EnvConfig.get();
        String lang = cfg.getString(EnvKey.RAG_LANG,
                cfg.getString(EnvKey.RAG_FULLTEXT_LANG, "english"));

        String sql = "SELECT id, content, source, chunk_index, metadata, " +
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
                                parseMetadata(rs.getString("metadata")),
                                null,
                                nullableChunkIndex(rs)
                        ));
                    }
                }
            }
            log.debug("pgvector keyword search returned {} documents", results.size());
        } catch (SQLException e) {
            throw new IllegalStateException("pgvector keyword search failed", e);
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
                    doc.score() * vectorWeight, doc.metadata(), null, doc.chunkIndex()));
        }
        for (Document doc : keywordDocs) {
            merged.merge(doc.id(), doc, (existing, incoming) -> {
                double combinedScore = existing.score() + incoming.score() * bm25Weight;
                return new Document(existing.id(), existing.content(), existing.source(),
                        combinedScore, existing.metadata(), null, existing.chunkIndex());
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

    // ==================== Explicit document context ====================

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
        String sql = String.format("""
                SELECT id, content, source, chunk_index, metadata
                FROM %s
                WHERE collection = ?
                  AND metadata ->> 'document_id' = ?
                  AND chunk_index BETWEEN ? AND ?
                ORDER BY chunk_index ASC, id ASC
                """, table);
        List<Document> documents = new ArrayList<>();
        try (Connection conn = PgConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, collection);
            ps.setString(2, documentId);
            ps.setInt(3, startIndex);
            ps.setInt(4, endIndex);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    documents.add(new Document(
                            String.valueOf(rs.getLong("id")),
                            rs.getString("content"),
                            rs.getString("source"),
                            0.0,
                            parseMetadata(rs.getString("metadata")),
                            null,
                            nullableChunkIndex(rs)
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read pgvector document context", e);
        }
        return List.copyOf(documents);
    }

    // ==================== PgVector 特有方法 ====================

    @Override
    public List<Document> searchText(String collection, String query, int topK) {
        return searchTextWithEvidence(collection, query, topK).documents();
    }

    @Override
    public SearchResult searchTextWithEvidence(String collection, String query, int topK) {
        if (embeddingProvider == null || !embeddingProvider.isAvailable()) {
            throw new IllegalStateException(
                    "searchText() requires an embedding provider. Set embedding.provider in model.conf.");
        }
        Embedding embedding;
        try {
            embedding = embeddingProvider.embed(query);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to embed query for RAG retrieval", e);
        }
        return searchVectorWithEvidence(collection, embedding.vector(), topK);
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
            throw new IllegalStateException(
                    "Failed to delete pgvector collection '" + collectionName + "'", e);
        }
    }

    /** Insert all chunks atomically. */
    public List<Long> insertBatch(List<DocumentEntry> entries) {
        String insertSql = String.format("""
                INSERT INTO %s (collection, source, content, embedding, chunk_index, metadata)
                VALUES (?, ?, ?, ?::vector, ?, ?::jsonb)
                RETURNING id
                """, table);
        String upsertSql = String.format("""
                INSERT INTO %s AS target (id, collection, source, content, embedding, chunk_index, metadata)
                VALUES (?, ?, ?, ?, ?::vector, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET
                    source = EXCLUDED.source,
                    content = EXCLUDED.content,
                    embedding = EXCLUDED.embedding,
                    chunk_index = EXCLUDED.chunk_index,
                    metadata = EXCLUDED.metadata
                WHERE target.collection = EXCLUDED.collection
                RETURNING id
                """, table);

        List<Long> ids = new ArrayList<>();
        Connection conn = null;
        try {
            conn = PgConnectionPool.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement insert = conn.prepareStatement(insertSql);
                 PreparedStatement upsert = conn.prepareStatement(upsertSql)) {
                for (DocumentEntry entry : entries) {
                    PreparedStatement statement = entry.id() == null ? insert : upsert;
                    int parameterIndex = 1;
                    if (entry.id() != null) {
                        statement.setLong(parameterIndex++, entry.id());
                    }
                    statement.setString(parameterIndex++,
                            entry.collection() != null ? entry.collection() : collection);
                    statement.setString(parameterIndex++, entry.source());
                    statement.setString(parameterIndex++, entry.content());
                    statement.setString(parameterIndex++, toVectorLiteral(entry.embedding()));
                    statement.setInt(parameterIndex++, entry.chunkIndex());
                    statement.setString(parameterIndex,
                            entry.metadata() != null ? mapToJson(entry.metadata()) : "{}");
                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException(
                                    "Knowledge chunk id belongs to another collection: " + entry.id());
                        }
                        ids.add(rs.getLong(1));
                    }
                }
            }

            conn.commit();
            log.info("Inserted {} documents into pgvector", entries.size());

        } catch (SQLException e) {
            log.error("pgvector batch insert failed, rolling back: {}", e.getMessage(), e);
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
            return JSON_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize knowledge document metadata", e);
        }
    }

    private static Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = JSON_MAPPER.readValue(json, METADATA_TYPE);
            return metadata == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse knowledge document metadata", e);
        }
    }

    private static int nullableChunkIndex(ResultSet resultSet) throws SQLException {
        int chunkIndex = resultSet.getInt("chunk_index");
        return resultSet.wasNull() ? -1 : chunkIndex;
    }

    private static KnowledgeChunkSummary toKnowledgeChunkSummary(
            String id,
            String source,
            int chunkIndex,
            Map<String, Object> metadata
    ) {
        return new KnowledgeChunkSummary(
                id,
                source,
                chunkIndex,
                stringMetadata(metadata, "document_id"),
                stringListMetadata(metadata, "heading_path"));
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

    private static Long parseCursorId(String lastId) {
        if (lastId == null) {
            return null;
        }
        try {
            return Long.parseLong(lastId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid pgvector knowledge page cursor", e);
        }
    }

    private static Long parseOptionalId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("pgvector knowledge chunk id must be numeric", exception);
        }
    }

    private static long parseRequiredId(String id) {
        Long parsedId = parseOptionalId(id);
        if (parsedId == null) {
            throw new IllegalArgumentException("knowledge chunk id is required");
        }
        return parsedId;
    }

    private static String requireCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Knowledge collection is required");
        }
        return collection.trim();
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

    // ==================== 记录类型 ====================

    public record DocumentEntry(
            Long id,
            String content,
            String source,
            float[] embedding,
            String collection,
            int chunkIndex,
            Map<String, Object> metadata
    ) {}

}
