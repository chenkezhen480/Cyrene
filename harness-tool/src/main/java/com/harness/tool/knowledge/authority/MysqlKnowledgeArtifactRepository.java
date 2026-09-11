package com.harness.tool.knowledge.authority;

import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.knowledge.ArtifactCursor;
import com.harness.core.knowledge.KnowledgeArtifact;
import com.harness.core.knowledge.KnowledgeArtifactType;
import com.harness.core.knowledge.KnowledgeIdentity;
import com.harness.core.knowledge.KnowledgeIngestJob;
import com.harness.core.model.PageResponse;
import com.harness.core.persistence.SqlConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MysqlKnowledgeArtifactRepository implements KnowledgeArtifactRepository {

    private final SqlConnectionProvider connectionProvider;

    public MysqlKnowledgeArtifactRepository() {
        this(MysqlConnectionPool::getConnection);
    }

    public MysqlKnowledgeArtifactRepository(SqlConnectionProvider connectionProvider) {
        this.connectionProvider = java.util.Objects.requireNonNull(
                connectionProvider, "connectionProvider");
    }

    @Override
    public Registration registerWithIngestJob(
            KnowledgeArtifact artifact,
            KnowledgeIngestJob ingestJob
    ) {
        String expectedArtifactId = KnowledgeIdentity.artifactId(
                artifact.tenantId(),
                artifact.collectionKey(),
                artifact.artifactType(),
                artifact.contentHash());
        if (!expectedArtifactId.equals(artifact.id())) {
            throw new IllegalArgumentException("artifact ID is not deterministic");
        }
        if (!artifact.id().equals(ingestJob.artifactId())) {
            throw new IllegalArgumentException("ingestJob must reference the registered artifact");
        }
        if (!sameScope(artifact.tenantId(), ingestJob.tenantId())
                || !artifact.collectionKey().equals(ingestJob.collectionKey())) {
            throw new IllegalArgumentException("artifact and ingestJob scope must match");
        }
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            KnowledgeArtifact storedArtifact = insertOrVerifyArtifact(connection, artifact);
            KnowledgeIngestJob storedJob = insertOrVerifyJob(connection, ingestJob);
            connection.commit();
            return new Registration(storedArtifact, storedJob);
        } catch (Exception e) {
            rollback(connection);
            throw new KnowledgePersistenceException(
                    "Failed to register knowledge artifact " + artifact.id(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public KnowledgeArtifact register(KnowledgeArtifact artifact) {
        String expectedArtifactId = KnowledgeIdentity.artifactId(
                artifact.tenantId(), artifact.collectionKey(),
                artifact.artifactType(), artifact.contentHash());
        if (!expectedArtifactId.equals(artifact.id())) {
            throw new IllegalArgumentException("artifact ID is not deterministic");
        }
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            KnowledgeArtifact stored = insertOrVerifyArtifact(connection, artifact);
            connection.commit();
            return stored;
        } catch (Exception e) {
            rollback(connection);
            throw new KnowledgePersistenceException(
                    "Failed to register knowledge artifact " + artifact.id(), e);
        } finally {
            close(connection);
        }
    }

    @Override
    public Optional<KnowledgeArtifact> findById(String artifactId) {
        String sql = "SELECT * FROM knowledge_artifacts WHERE id = ?";
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapArtifact(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to read artifact " + artifactId, e);
        }
    }

    @Override
    public Optional<KnowledgeArtifact> findByContent(
            String tenantId,
            String collectionKey,
            String contentHash,
            KnowledgeArtifactType artifactType
    ) {
        String sql = """
                SELECT * FROM knowledge_artifacts
                WHERE tenant_id <=> ? AND collection_key = ?
                  AND content_hash = ? AND artifact_type = ?
                ORDER BY id LIMIT 1
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, collectionKey);
            statement.setString(3, contentHash);
            statement.setString(4, artifactType.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapArtifact(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to find artifact by content", e);
        }
    }

    @Override
    public PageResponse<KnowledgeArtifact> findPage(
            String tenantId,
            String collectionKey,
            String fileNamePrefix,
            ArtifactCursor cursor,
            int limit
    ) {
        validateLimit(limit);
        String normalizedPrefix = fileNamePrefix == null ? "" : fileNamePrefix.trim();
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM knowledge_artifacts
                WHERE tenant_id <=> ? AND collection_key = ?
                """);
        if (!normalizedPrefix.isEmpty()) {
            sql.append(" AND file_name LIKE ? ESCAPE '\\\\'");
        }
        if (cursor != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");

        List<KnowledgeArtifact> artifacts = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, tenantId);
            statement.setString(parameter++, collectionKey);
            if (!normalizedPrefix.isEmpty()) {
                statement.setString(parameter++, escapeLike(normalizedPrefix) + "%");
            }
            if (cursor != null) {
                Timestamp timestamp = Timestamp.from(cursor.createdAt());
                statement.setTimestamp(parameter++, timestamp);
                statement.setTimestamp(parameter++, timestamp);
                statement.setString(parameter++, cursor.artifactId());
            }
            statement.setInt(parameter, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    artifacts.add(mapArtifact(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to list knowledge artifacts", e);
        }
        return PageResponse.fromFetched(
                artifacts,
                limit,
                artifact -> artifact.createdAt() + "|" + artifact.id());
    }

    @Override
    public boolean storageUriExists(String storageUri) {
        String sql = "SELECT 1 FROM knowledge_artifacts WHERE storage_uri = ? LIMIT 1";
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storageUri);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to check artifact storage URI", e);
        }
    }

    private KnowledgeArtifact insertOrVerifyArtifact(
            Connection connection,
            KnowledgeArtifact artifact
    ) throws SQLException {
        String insertSql = """
                INSERT INTO knowledge_artifacts
                    (id, tenant_id, collection_key, artifact_type, file_name, media_type,
                     content_hash, storage_uri, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """;
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setString(1, artifact.id());
            statement.setString(2, artifact.tenantId());
            statement.setString(3, artifact.collectionKey());
            statement.setString(4, artifact.artifactType().name());
            statement.setString(5, artifact.fileName());
            statement.setString(6, artifact.mediaType());
            statement.setString(7, artifact.contentHash());
            statement.setString(8, artifact.storageUri());
            statement.setString(9, artifact.status().name().toLowerCase(java.util.Locale.ROOT));
            statement.setTimestamp(10, Timestamp.from(artifact.createdAt()));
            statement.executeUpdate();
        }
        KnowledgeArtifact stored = findArtifactForUpdate(connection, artifact.id()).orElseThrow(
                () -> new SQLException("Artifact insert did not create a row"));
        if (!sameArtifactIdentity(stored, artifact)) {
            throw new SQLException("Artifact ID is already bound to different content or scope");
        }
        return stored;
    }

    private KnowledgeIngestJob insertOrVerifyJob(
            Connection connection,
            KnowledgeIngestJob job
    ) throws SQLException {
        String insertSql = """
                INSERT INTO knowledge_tasks
                    (task_type, id, artifact_id, tenant_id, collection_key, status, attempts, available_at,
                     claimed_at, converted_artifact_id, source_concept_id, source_revision_id,
                     error_message, created_at, completed_at)
                VALUES ('document_ingest', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """;
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            bindJob(statement, job);
            statement.executeUpdate();
        }
        KnowledgeIngestJob stored = findJobForUpdate(connection, job.id()).orElseThrow(
                () -> new SQLException("Ingest job insert did not create a row"));
        if (!stored.artifactId().equals(job.artifactId())
                || !sameScope(stored.tenantId(), job.tenantId())
                || !stored.collectionKey().equals(job.collectionKey())) {
            throw new SQLException("Ingest Job ID is already bound to a different artifact or scope");
        }
        return stored;
    }

    private Optional<KnowledgeArtifact> findArtifactForUpdate(Connection connection, String id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM knowledge_artifacts WHERE id = ? FOR UPDATE")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapArtifact(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<KnowledgeIngestJob> findJobForUpdate(Connection connection, String id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM knowledge_tasks WHERE task_type = 'document_ingest' AND id = ? FOR UPDATE")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapJob(resultSet)) : Optional.empty();
            }
        }
    }

    static KnowledgeIngestJob mapJob(ResultSet resultSet) throws SQLException {
        return new KnowledgeIngestJob(
                resultSet.getString("id"),
                resultSet.getString("artifact_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("collection_key"),
                KnowledgeIngestJob.Status.valueOf(
                        resultSet.getString("status").toUpperCase(java.util.Locale.ROOT)),
                resultSet.getInt("attempts"),
                instant(resultSet, "available_at"),
                instant(resultSet, "claimed_at"),
                resultSet.getString("converted_artifact_id"),
                resultSet.getString("source_concept_id"),
                resultSet.getString("source_revision_id"),
                resultSet.getString("error_message"),
                instant(resultSet, "created_at"),
                instant(resultSet, "completed_at"));
    }

    static void bindJob(PreparedStatement statement, KnowledgeIngestJob job) throws SQLException {
        statement.setString(1, job.id());
        statement.setString(2, job.artifactId());
        statement.setString(3, job.tenantId());
        statement.setString(4, job.collectionKey());
        statement.setString(5, job.status().name().toLowerCase(java.util.Locale.ROOT));
        statement.setInt(6, job.attempts());
        setInstant(statement, 7, job.availableAt());
        setInstant(statement, 8, job.claimedAt());
        statement.setString(9, job.convertedArtifactId());
        statement.setString(10, job.sourceConceptId());
        statement.setString(11, job.sourceRevisionId());
        statement.setString(12, job.errorMessage());
        setInstant(statement, 13, job.createdAt());
        setInstant(statement, 14, job.completedAt());
    }

    private static KnowledgeArtifact mapArtifact(ResultSet resultSet) throws SQLException {
        return new KnowledgeArtifact(
                resultSet.getString("id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("collection_key"),
                KnowledgeArtifactType.valueOf(resultSet.getString("artifact_type")),
                resultSet.getString("file_name"),
                resultSet.getString("media_type"),
                resultSet.getString("content_hash"),
                resultSet.getString("storage_uri"),
                KnowledgeArtifact.Status.valueOf(
                        resultSet.getString("status").toUpperCase(java.util.Locale.ROOT)),
                instant(resultSet, "created_at"));
    }

    private static boolean sameArtifactIdentity(
            KnowledgeArtifact left,
            KnowledgeArtifact right
    ) {
        return sameScope(left.tenantId(), right.tenantId())
                && left.collectionKey().equals(right.collectionKey())
                && left.artifactType() == right.artifactType()
                && left.contentHash().equals(right.contentHash())
                && left.storageUri().equals(right.storageUri());
    }

    private static boolean sameScope(String left, String right) {
        return java.util.Objects.equals(normalizeTenant(left), normalizeTenant(right));
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    static void setInstant(PreparedStatement statement, int index, Instant instant)
            throws SQLException {
        if (instant == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(instant));
        }
    }

    private static void rollback(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private static void close(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.setAutoCommit(true);
            connection.close();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
