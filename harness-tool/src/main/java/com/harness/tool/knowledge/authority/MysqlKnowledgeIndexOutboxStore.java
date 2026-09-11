package com.harness.tool.knowledge.authority;

import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.knowledge.KnowledgeIndexOperation;
import com.harness.core.knowledge.KnowledgeIndexTask;
import com.harness.core.knowledge.KnowledgeIndexTaskStatus;
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

public final class MysqlKnowledgeIndexOutboxStore implements KnowledgeIndexOutboxStore {

    private final SqlConnectionProvider connectionProvider;

    public MysqlKnowledgeIndexOutboxStore() {
        this(MysqlConnectionPool::getConnection);
    }

    public MysqlKnowledgeIndexOutboxStore(SqlConnectionProvider connectionProvider) {
        this.connectionProvider = java.util.Objects.requireNonNull(
                connectionProvider, "connectionProvider");
    }

    @Override
    public Optional<KnowledgeIndexTask> findById(long taskId) {
        try (Connection connection = connectionProvider.getConnection()) {
            return findById(connection, taskId);
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to read index task " + taskId, e);
        }
    }

    @Override
    public Optional<KnowledgeIndexTask> claimNext(Instant now) {
        String selectSql = """
                SELECT * FROM knowledge_tasks
                WHERE task_type = 'vector_index' AND status = 'pending' AND available_at <= ?
                  AND (operation <> 'UPSERT_CURRENT' OR NOT EXISTS (
                    SELECT 1 FROM knowledge_tasks ingest WHERE ingest.task_type = 'document_ingest'
                      AND ingest.source_revision_id = knowledge_tasks.revision_id AND ingest.status <> 'indexed'))
                ORDER BY available_at, sequence_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """;
        String updateSql = """
                UPDATE knowledge_tasks
                SET status = 'in_progress', attempts = attempts + 1,
                    claimed_at = ?, error_message = NULL
                WHERE task_type = 'vector_index' AND sequence_id = ? AND status = 'pending'
                """;
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            long taskId;
            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    taskId = resultSet.getLong("sequence_id");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setLong(2, taskId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Index task claim lost");
                }
            }
            KnowledgeIndexTask task = findById(connection, taskId).orElseThrow();
            connection.commit();
            return Optional.of(task);
        } catch (Exception e) {
            rollback(connection);
            throw new KnowledgePersistenceException("Failed to claim index task", e);
        } finally {
            close(connection);
        }
    }

    @Override
    public void markCompleted(long taskId, Instant completedAt) {
        updateInProgress(taskId, "completed", completedAt, null, null);
    }

    @Override
    public void reschedule(long taskId, Instant availableAt, String errorMessage) {
        updateInProgress(taskId, "pending", null, availableAt, errorMessage);
    }

    @Override
    public void markFailed(long taskId, Instant completedAt, String errorMessage) {
        updateInProgress(taskId, "failed", completedAt, null, errorMessage);
    }

    @Override
    public void replayFailed(long taskId, Instant availableAt) {
        String sql = """
                UPDATE knowledge_tasks
                SET status = 'pending', attempts = 0, available_at = ?, claimed_at = NULL,
                    completed_at = NULL, error_message = NULL
                WHERE task_type = 'vector_index' AND sequence_id = ? AND status = 'failed'
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(availableAt));
            statement.setLong(2, taskId);
            requireSingleUpdate(statement, "Index task is not failed: " + taskId);
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to replay index task " + taskId, e);
        }
    }

    @Override
    public int recoverStuck(Instant claimedBefore, Instant availableAt) {
        String sql = """
                UPDATE knowledge_tasks
                SET status = 'pending', available_at = ?, claimed_at = NULL,
                    error_message = 'Recovered after claim timeout'
                WHERE task_type = 'vector_index' AND status = 'in_progress' AND claimed_at < ?
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(availableAt));
            statement.setTimestamp(2, Timestamp.from(claimedBefore));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to recover stuck index tasks", e);
        }
    }

    @Override
    public PageResponse<KnowledgeIndexTask> findPage(long afterId, int limit) {
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId must not be negative");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String sql = """
                SELECT * FROM knowledge_tasks
                WHERE task_type = 'vector_index' AND sequence_id > ? ORDER BY sequence_id LIMIT ?
                """;
        List<KnowledgeIndexTask> tasks = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tasks.add(map(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to list index tasks", e);
        }
        return PageResponse.fromFetched(tasks, limit, task -> Long.toString(task.id()));
    }

    @Override
    public PageResponse<KnowledgeIndexTask> findPageByConceptIds(
            List<String> conceptIds,
            long afterId,
            int limit
    ) {
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId must not be negative");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        java.util.LinkedHashSet<String> uniqueIds = new java.util.LinkedHashSet<>();
        if (conceptIds != null) {
            for (String conceptId : conceptIds) {
                if (conceptId == null || conceptId.isBlank()) {
                    throw new IllegalArgumentException("conceptIds contains a blank ID");
                }
                uniqueIds.add(conceptId.trim());
            }
        }
        if (uniqueIds.isEmpty()) {
            return PageResponse.fromFetched(List.of(), limit, task -> "");
        }
        if (uniqueIds.size() > 100) {
            throw new IllegalArgumentException("conceptIds must not exceed 100 entries");
        }
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(uniqueIds.size(), "?"));
        String sql = "SELECT * FROM knowledge_tasks WHERE task_type = 'vector_index' AND sequence_id > ? AND concept_id IN ("
                + placeholders + ") ORDER BY sequence_id ASC LIMIT ?";
        List<KnowledgeIndexTask> tasks = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setLong(parameter++, afterId);
            for (String conceptId : uniqueIds) {
                statement.setString(parameter++, conceptId);
            }
            statement.setInt(parameter, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tasks.add(map(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to list scoped index tasks", e);
        }
        return PageResponse.fromFetched(tasks, limit, task -> Long.toString(task.id()));
    }

    private void updateInProgress(
            long taskId,
            String status,
            Instant completedAt,
            Instant availableAt,
            String errorMessage
    ) {
        String sql = """
                UPDATE knowledge_tasks
                SET status = ?, completed_at = ?, available_at = COALESCE(?, available_at),
                    claimed_at = NULL, error_message = ?,
                    payload = CASE WHEN status = 'completed' THEN NULL ELSE payload END
                WHERE task_type = 'vector_index' AND sequence_id = ? AND status = 'in_progress'
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            MysqlKnowledgeArtifactRepository.setInstant(statement, 2, completedAt);
            MysqlKnowledgeArtifactRepository.setInstant(statement, 3, availableAt);
            statement.setString(4, errorMessage);
            statement.setLong(5, taskId);
            requireSingleUpdate(statement, "Index task is not in progress: " + taskId);
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to update index task " + taskId, e);
        }
    }

    private Optional<KnowledgeIndexTask> findById(Connection connection, long taskId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM knowledge_tasks WHERE task_type = 'vector_index' AND sequence_id = ?")) {
            statement.setLong(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private static KnowledgeIndexTask map(ResultSet resultSet) throws SQLException {
        return new KnowledgeIndexTask(
                resultSet.getLong("sequence_id"),
                resultSet.getString("concept_id"),
                resultSet.getString("revision_id"),
                KnowledgeIndexOperation.valueOf(resultSet.getString("operation")),
                KnowledgeIndexTaskStatus.valueOf(
                        resultSet.getString("status").toUpperCase(java.util.Locale.ROOT)),
                resultSet.getInt("attempts"),
                MysqlKnowledgeArtifactRepository.instant(resultSet, "available_at"),
                MysqlKnowledgeArtifactRepository.instant(resultSet, "claimed_at"),
                MysqlKnowledgeArtifactRepository.instant(resultSet, "completed_at"),
                resultSet.getString("error_message"),
                MysqlKnowledgeArtifactRepository.instant(resultSet, "created_at"));
    }

    private static void requireSingleUpdate(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new KnowledgePersistenceException(message);
        }
    }

    private static void rollback(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Preserve original failure.
            }
        }
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.setAutoCommit(true);
                connection.close();
            } catch (SQLException ignored) {
                // Preserve original failure.
            }
        }
    }
}
