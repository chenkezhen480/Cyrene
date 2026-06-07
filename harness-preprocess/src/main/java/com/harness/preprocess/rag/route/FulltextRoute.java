package com.harness.preprocess.rag.route;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.preprocess.rag.RagRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Fulltext/BM25 retrieval route using PostgreSQL tsvector/tsquery.
 * Requires the content_tsv tsvector column and GIN index on the knowledge_documents table.
 */
public class FulltextRoute implements RetrievalRoute {

    private static final Logger log = LoggerFactory.getLogger(FulltextRoute.class);

    private final String dbUrl;
    private final String dbUser;
    private final String dbPass;
    private final String table;
    private final String collection;
    private final String lang;
    private final int topK;
    private final double scoreThreshold;

    public FulltextRoute() {
        EnvConfig cfg = EnvConfig.get();
        this.dbUrl = cfg.getString(EnvKey.RAG_PG_URL, "jdbc:postgresql://localhost:5432/agent");
        this.dbUser = cfg.getString(EnvKey.RAG_PG_USER, "postgres");
        this.dbPass = cfg.getString(EnvKey.RAG_PG_PASS, "");
        this.table = cfg.getString(EnvKey.RAG_PG_TABLE, "knowledge_documents");
        this.collection = cfg.getString(EnvKey.RAG_COLLECTION, "default");
        this.lang = cfg.getString(EnvKey.RAG_FULLTEXT_LANG, "english");
        this.topK = cfg.getInt(EnvKey.RAG_TOP_K, 5);
        this.scoreThreshold = cfg.getDouble(EnvKey.RAG_SCORE_THRESHOLD, 0.1);
    }

    @Override
    public List<RagRetriever.RagDocument> retrieve(String query) {
        if (query == null || query.isBlank()) return List.of();

        String sql = "SELECT id, content, source, " +
                "ts_rank_cd(to_tsvector(?, content), plainto_tsquery(?, ?)) AS score " +
                "FROM " + table + " " +
                "WHERE collection = ? " +
                "AND to_tsvector(?, content) @@ plainto_tsquery(?, ?) " +
                "ORDER BY score DESC " +
                "LIMIT ?";

        List<RagRetriever.RagDocument> results = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
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
                    if (score >= scoreThreshold) {
                        results.add(new RagRetriever.RagDocument(
                                rs.getString("id"),
                                rs.getString("content"),
                                rs.getString("source"),
                                score
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("[FulltextRoute] Query failed: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public String routeName() {
        return "fulltext";
    }

    @Override
    public boolean isAvailable() {
        return EnvConfig.get().getBool(EnvKey.RAG_FULLTEXT_ENABLED, false);
    }
}
