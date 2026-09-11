package com.harness.tool.knowledge.authority;

import com.harness.core.env.MysqlConnectionPool;
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

public final class MysqlKnowledgeIngestJobStore implements KnowledgeIngestJobStore {

    private final SqlConnectionProvider connectionProvider;
    private final MysqlKnowledgeRepository knowledgeRepository;

    public MysqlKnowledgeIngestJobStore() {
        this(MysqlConnectionPool::getConnection);
    }

    public MysqlKnowledgeIngestJobStore(SqlConnectionProvider connectionProvider) {
        this.connectionProvider = java.util.Objects.requireNonNull(
                connectionProvider, "connectionProvider");
        this.knowledgeRepository = new MysqlKnowledgeRepository(
                connectionProvider, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    public MysqlKnowledgeIngestJobStore(KnowledgeRepository repository) {
        this.connectionProvider = MysqlConnectionPool::getConnection;
        if (!(repository instanceof MysqlKnowledgeRepository mysql))
            throw new IllegalArgumentException("MySQL ingest requires MySQL authority");
        this.knowledgeRepository = mysql;
    }

    @Override
    public Optional<KnowledgeIngestJob> findById(String jobId) {
        try (Connection connection = connectionProvider.getConnection()) {
            return findById(connection, jobId, false);
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to read ingest job " + jobId, e);
        }
    }

    @Override
    public Optional<KnowledgeIngestJob> claimNext(Instant now) {
        String selectSql = """
                SELECT * FROM knowledge_tasks
                WHERE task_type = 'document_ingest' AND status IN ('uploaded', 'converted', 'compiled')
                  AND claimed_at IS NULL AND available_at <= ?
                ORDER BY available_at, id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """;
        String updateSql = """
                UPDATE knowledge_tasks
                SET claimed_at = ?, attempts = attempts + 1, error_message = NULL
                WHERE task_type = 'document_ingest' AND id = ? AND claimed_at IS NULL
                """;
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            String jobId;
            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    jobId = resultSet.getString("id");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setString(2, jobId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Ingest Job claim lost");
                }
            }
            KnowledgeIngestJob claimed = findById(connection, jobId, false).orElseThrow();
            connection.commit();
            return Optional.of(claimed);
        } catch (Exception e) {
            rollback(connection);
            throw new KnowledgePersistenceException("Failed to claim ingest job", e);
        } finally {
            close(connection);
        }
    }

    @Override
    public Optional<KnowledgeIngestJob> claim(String jobId, Instant now) {
        String sql = """
                UPDATE knowledge_tasks
                SET claimed_at = ?, attempts = attempts + 1, error_message = NULL
                WHERE task_type = 'document_ingest' AND id = ? AND claimed_at IS NULL AND available_at <= ?
                  AND status IN ('uploaded', 'converted', 'compiled')
                """;
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                Timestamp claimedAt = Timestamp.from(now);
                statement.setTimestamp(1, claimedAt);
                statement.setString(2, jobId);
                statement.setTimestamp(3, claimedAt);
                if (statement.executeUpdate() == 0) {
                    connection.commit();
                    return Optional.empty();
                }
            }
            KnowledgeIngestJob claimed = findById(connection, jobId, false).orElseThrow();
            connection.commit();
            return Optional.of(claimed);
        } catch (Exception e) {
            rollback(connection);
            throw new KnowledgePersistenceException("Failed to claim ingest job " + jobId, e);
        } finally {
            close(connection);
        }
    }

    @Override
    public KnowledgeIngestJob advance(
            String jobId,
            KnowledgeIngestJob.Status expectedStatus,
            KnowledgeIngestJob.Status nextStatus,
            String convertedArtifactId,
            String sourceConceptId,
            String sourceRevisionId,
            Instant completedAt
    ) {
        requireTransition(expectedStatus, nextStatus);
        if (nextStatus == KnowledgeIngestJob.Status.INDEXED && completedAt == null) {
            throw new IllegalArgumentException("completedAt is required when indexing completes");
        }
        String sql = """
                UPDATE knowledge_tasks
                SET status = ?, converted_artifact_id = COALESCE(?, converted_artifact_id),
                    source_concept_id = COALESCE(?, source_concept_id),
                    source_revision_id = COALESCE(?, source_revision_id),
                    completed_at = ?, claimed_at = NULL, error_message = NULL
                WHERE task_type = 'document_ingest' AND id = ? AND status = ? AND claimed_at IS NOT NULL
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storage(nextStatus));
            statement.setString(2, convertedArtifactId);
            statement.setString(3, sourceConceptId);
            statement.setString(4, sourceRevisionId);
            MysqlKnowledgeArtifactRepository.setInstant(statement, 5, completedAt);
            statement.setString(6, jobId);
            statement.setString(7, storage(expectedStatus));
            if (statement.executeUpdate() != 1) {
                throw new KnowledgePersistenceException(
                        "Ingest Job is not claimed in expected state: " + jobId);
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to advance ingest job " + jobId, e);
        }
        return findById(jobId).orElseThrow();
    }

    @Override
    public KnowledgeIngestJob commitCompilation(
            String jobId,
            KnowledgeRevisionChange change
    ) {
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            KnowledgeIngestJob job = findById(connection, jobId, true).orElseThrow(
                    () -> new KnowledgePersistenceException("Ingest Job not found: " + jobId));
            if (job.status() == KnowledgeIngestJob.Status.COMPILED
                    && change.revision().id().equals(job.sourceRevisionId())) {
                connection.commit();
                return job;
            }
            if (job.status() != KnowledgeIngestJob.Status.CONVERTED
                    || job.claimedAt() == null) {
                throw new KnowledgePersistenceException(
                        "Ingest Job is not claimed after conversion: " + jobId);
            }
            if (job.sourceConceptId() != null
                    && !job.sourceConceptId().equals(change.concept().id())) {
                throw new KnowledgePersistenceException(
                        "Ingest Job source Concept differs from compiled Concept: " + jobId);
            }
            knowledgeRepository.commitRevisionChange(connection, change);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE knowledge_tasks
                    SET status = 'compiled', source_concept_id = ?, source_revision_id = ?,
                        claimed_at = NULL, error_message = NULL
                    WHERE task_type = 'document_ingest' AND id = ? AND status = 'converted' AND claimed_at IS NOT NULL
                    """)) {
                statement.setString(1, change.concept().id());
                statement.setString(2, change.revision().id());
                statement.setString(3, jobId);
                if (statement.executeUpdate() != 1) {
                    throw new KnowledgePersistenceException(
                            "Ingest Job compilation state conflict: " + jobId);
                }
            }
            connection.commit();
            return findById(jobId).orElseThrow();
        } catch (Exception e) {
            rollback(connection);
            if (e instanceof KnowledgePersistenceException persistenceException) {
                throw persistenceException;
            }
            throw new KnowledgePersistenceException(
                    "Failed to commit Ingest Job compilation " + jobId, e);
        } finally {
            close(connection);
        }
    }

    @Override
    public void reschedule(String jobId, Instant availableAt, String errorMessage) {
        updateClaimed(jobId, """
                UPDATE knowledge_tasks
                SET available_at = ?, claimed_at = NULL, error_message = ?
                WHERE task_type = 'document_ingest' AND id = ? AND claimed_at IS NOT NULL
                """, availableAt, errorMessage);
    }

    @Override
    public void markFailed(String jobId, Instant completedAt, String errorMessage) {
        updateClaimed(jobId, """
                UPDATE knowledge_tasks
                SET status = 'failed', completed_at = ?, claimed_at = NULL, error_message = ?
                WHERE task_type = 'document_ingest' AND id = ? AND claimed_at IS NOT NULL
                """, completedAt, errorMessage);
    }

    @Override
    public void replayFailed(String jobId, Instant availableAt) {
        String sql = """
                UPDATE knowledge_tasks
                SET status = CASE
                        WHEN source_revision_id IS NOT NULL THEN 'compiled'
                        WHEN converted_artifact_id IS NOT NULL THEN 'converted'
                        ELSE 'uploaded'
                    END,
                    attempts = 0, available_at = ?, claimed_at = NULL,
                    completed_at = NULL, error_message = NULL
                WHERE task_type = 'document_ingest' AND id = ? AND status = 'failed'
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(availableAt));
            statement.setString(2, jobId);
            if (statement.executeUpdate() != 1) {
                throw new KnowledgePersistenceException("Ingest Job is not failed: " + jobId);
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to replay ingest job " + jobId, e);
        }
    }

    @Override
    public int recoverStuck(Instant claimedBefore, Instant availableAt) {
        String sql = """
                UPDATE knowledge_tasks
                SET claimed_at = NULL, available_at = ?, error_message = 'Recovered after claim timeout'
                WHERE task_type = 'document_ingest' AND claimed_at < ? AND status IN ('uploaded', 'converted', 'compiled')
                """;
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(availableAt));
            statement.setTimestamp(2, Timestamp.from(claimedBefore));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to recover stuck ingest jobs", e);
        }
    }

    @Override
    public PageResponse<KnowledgeIngestJob> findPage(String afterJobId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        String sql = """
                SELECT * FROM knowledge_tasks
                WHERE task_type = 'document_ingest' AND id > ? ORDER BY id LIMIT ?
                """;
        List<KnowledgeIngestJob> jobs = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, afterJobId == null ? "" : afterJobId);
            statement.setInt(2, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    jobs.add(MysqlKnowledgeArtifactRepository.mapJob(resultSet));
                }
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to list ingest jobs", e);
        }
        return PageResponse.fromFetched(jobs, limit, KnowledgeIngestJob::id);
    }

    private void updateClaimed(
            String jobId,
            String sql,
            Instant instant,
            String errorMessage
    ) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(instant));
            statement.setString(2, errorMessage);
            statement.setString(3, jobId);
            if (statement.executeUpdate() != 1) {
                throw new KnowledgePersistenceException("Ingest Job is not claimed: " + jobId);
            }
        } catch (SQLException e) {
            throw new KnowledgePersistenceException("Failed to update ingest job " + jobId, e);
        }
    }

    private Optional<KnowledgeIngestJob> findById(
            Connection connection,
            String jobId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM knowledge_tasks WHERE task_type = 'document_ingest' AND id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(MysqlKnowledgeArtifactRepository.mapJob(resultSet))
                        : Optional.empty();
            }
        }
    }

    private static void requireTransition(
            KnowledgeIngestJob.Status expected,
            KnowledgeIngestJob.Status next
    ) {
        boolean valid = expected == KnowledgeIngestJob.Status.UPLOADED
                && next == KnowledgeIngestJob.Status.CONVERTED
                || expected == KnowledgeIngestJob.Status.CONVERTED
                && next == KnowledgeIngestJob.Status.COMPILED
                || expected == KnowledgeIngestJob.Status.COMPILED
                && next == KnowledgeIngestJob.Status.INDEXED;
        if (!valid) {
            throw new IllegalArgumentException(
                    "invalid ingest transition: " + expected + " -> " + next);
        }
    }

    private static String storage(KnowledgeIngestJob.Status status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
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
