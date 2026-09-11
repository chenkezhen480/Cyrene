package com.harness.tool.knowledge.authority;

import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.persistence.SqlConnectionProvider;
import com.harness.graph.build.GraphChangeSetCodec;
import com.harness.graph.model.GraphChangeSet;
import com.harness.graph.model.GraphMutationResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** MySQL authority for retryable Neo4j-to-Wiki mutation Sagas. */
public final class MysqlKnowledgeGraphMutationJobStore
        implements KnowledgeGraphMutationJobStore {

    private final SqlConnectionProvider connectionProvider;
    private final GraphChangeSetCodec codec;

    public MysqlKnowledgeGraphMutationJobStore() {
        this(MysqlConnectionPool::getConnection, new GraphChangeSetCodec());
    }

    public MysqlKnowledgeGraphMutationJobStore(
            SqlConnectionProvider connectionProvider,
            GraphChangeSetCodec codec
    ) {
        this.connectionProvider = java.util.Objects.requireNonNull(
                connectionProvider, "connectionProvider");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
    }

    @Override
    public KnowledgeGraphMutationJob register(
            GraphChangeSet changeSet,
            String tenantId,
            String sourceRevisionId,
            Instant now
    ) {
        GraphChangeSetCodec.Encoded encoded = codec.encode(changeSet);
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            KnowledgeGraphMutationJob existing = findById(
                    connection, changeSet.requestId(), true).orElse(null);
            if (existing != null) {
                if (!existing.payloadHash().equals(encoded.payloadHash())) {
                    throw new IllegalArgumentException(
                            "Graph mutation requestId is already bound to another payload");
                }
                connection.commit();
                return existing;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO knowledge_tasks (task_type, 
                        id, tenant_id, graph_id, schema_id, source_revision_id,
                        payload_hash, canonical_payload, status, attempts,
                        available_at, created_at
                    ) VALUES ('graph_mutation', ?, ?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?)
                    """)) {
                statement.setString(1, changeSet.requestId());
                statement.setString(2, normalize(tenantId));
                statement.setString(3, changeSet.graphId());
                statement.setString(4, changeSet.schemaId());
                statement.setString(5, normalize(sourceRevisionId));
                statement.setString(6, encoded.payloadHash());
                statement.setString(7, encoded.canonicalPayload());
                setInstant(statement, 8, now);
                setInstant(statement, 9, now);
                statement.executeUpdate();
            }
            KnowledgeGraphMutationJob inserted = findById(
                    connection, changeSet.requestId(), false).orElseThrow();
            connection.commit();
            return inserted;
        } catch (Exception exception) {
            rollback(connection, exception);
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw persistence("Failed to register graph mutation "
                    + changeSet.requestId(), exception);
        } finally {
            close(connection);
        }
    }

    @Override
    public Optional<KnowledgeGraphMutationJob> findById(String requestId) {
        try (Connection connection = connectionProvider.getConnection()) {
            return findById(connection, required(requestId), false);
        } catch (SQLException exception) {
            throw persistence("Failed to find graph mutation " + requestId, exception);
        }
    }

    @Override
    public Optional<KnowledgeGraphMutationJob> claim(String requestId, Instant now) {
        return claimById(required(requestId), now);
    }

    @Override
    public Optional<KnowledgeGraphMutationJob> claimNext(Instant now) {
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            String requestId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id
                    FROM knowledge_tasks
                    WHERE task_type = 'graph_mutation' AND status IN ('pending', 'graph_committed')
                      AND claimed_at IS NULL AND available_at <= ?
                    ORDER BY available_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """)) {
                setInstant(statement, 1, now);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    requestId = resultSet.getString(1);
                }
            }
            claimUpdate(connection, requestId, now);
            KnowledgeGraphMutationJob claimed = findById(
                    connection, requestId, false).orElseThrow();
            connection.commit();
            return Optional.of(claimed);
        } catch (Exception exception) {
            rollback(connection, exception);
            throw persistence("Failed to claim next graph mutation", exception);
        } finally {
            close(connection);
        }
    }

    @Override
    public KnowledgeGraphMutationJob markGraphCommitted(
            String requestId,
            GraphMutationResult result,
            Instant now
    ) {
        if (!required(requestId).equals(result.requestId()) || !result.committed()) {
            throw new IllegalArgumentException("Committed graph result must match requestId");
        }
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE knowledge_tasks
                     SET status = 'graph_committed', graph_node_count = ?,
                         graph_relation_count = ?, graph_committed_at = ?,
                         claimed_at = NULL, error_message = NULL
                     WHERE task_type = 'graph_mutation' AND id = ? AND status = 'pending' AND claimed_at IS NOT NULL
                     """)) {
            statement.setInt(1, result.nodeCount());
            statement.setInt(2, result.relationCount());
            setInstant(statement, 3, now);
            statement.setString(4, requestId);
            requireOne(statement.executeUpdate(), "Graph mutation is not claimed as pending");
        } catch (SQLException exception) {
            throw persistence("Failed to mark graph mutation committed " + requestId, exception);
        }
        return findById(requestId).orElseThrow();
    }

    @Override
    public KnowledgeGraphMutationJob completeKnowledge(
            String requestId,
            String revisionId,
            Instant now
    ) {
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            KnowledgeGraphMutationJob job = findById(
                    connection, required(requestId), true).orElseThrow();
            if (job.status() == KnowledgeGraphMutationJob.Status.KNOWLEDGE_COMMITTED) {
                connection.commit();
                return job;
            }
            if (job.status() != KnowledgeGraphMutationJob.Status.GRAPH_COMMITTED
                    || job.claimedAt() == null) {
                throw new KnowledgePersistenceException(
                        "Graph mutation is not claimed after graph commit: " + requestId);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE knowledge_tasks
                    SET status = 'knowledge_committed', completed_at = ?, result_revision_id = ?,
                        claimed_at = NULL, error_message = NULL
                    WHERE task_type = 'graph_mutation' AND id = ? AND status = 'graph_committed'
                      AND claimed_at IS NOT NULL
                    """)) {
                setInstant(statement, 1, now);
                statement.setString(2, required(revisionId));
                statement.setString(3, requestId);
                requireOne(statement.executeUpdate(),
                        "Graph mutation knowledge commit changed concurrently");
            }
            connection.commit();
            return findById(requestId).orElseThrow();
        } catch (Exception exception) {
            rollback(connection, exception);
            throw persistence("Failed to complete graph mutation knowledge "
                    + requestId, exception);
        } finally {
            close(connection);
        }
    }

    @Override
    public void reschedule(String requestId, Instant availableAt, String errorMessage) {
        updateClaimed(requestId, """
                UPDATE knowledge_tasks
                SET available_at = ?, claimed_at = NULL, error_message = ?
                WHERE task_type = 'graph_mutation' AND id = ? AND claimed_at IS NOT NULL
                """, availableAt, errorMessage);
    }

    @Override
    public void markFailed(String requestId, Instant completedAt, String errorMessage) {
        updateClaimed(requestId, """
                UPDATE knowledge_tasks
                SET status = 'failed', completed_at = ?, claimed_at = NULL,
                    error_message = ?
                WHERE task_type = 'graph_mutation' AND id = ? AND claimed_at IS NOT NULL
                """, completedAt, errorMessage);
    }

    @Override
    public int recoverStuck(Instant claimedBefore, Instant availableAt) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE knowledge_tasks
                     SET claimed_at = NULL, available_at = ?,
                         error_message = 'Recovered after claim timeout'
                     WHERE task_type = 'graph_mutation' AND claimed_at < ?
                       AND status IN ('pending', 'graph_committed')
                     """)) {
            setInstant(statement, 1, availableAt);
            setInstant(statement, 2, claimedBefore);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw persistence("Failed to recover stuck graph mutations", exception);
        }
    }

    private Optional<KnowledgeGraphMutationJob> claimById(String requestId, Instant now) {
        Connection connection = null;
        try {
            connection = connectionProvider.getConnection();
            connection.setAutoCommit(false);
            if (!claimUpdate(connection, requestId, now)) {
                connection.commit();
                return Optional.empty();
            }
            KnowledgeGraphMutationJob claimed = findById(
                    connection, requestId, false).orElseThrow();
            connection.commit();
            return Optional.of(claimed);
        } catch (Exception exception) {
            rollback(connection, exception);
            throw persistence("Failed to claim graph mutation " + requestId, exception);
        } finally {
            close(connection);
        }
    }

    private boolean claimUpdate(Connection connection, String requestId, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE knowledge_tasks
                SET claimed_at = ?, attempts = attempts + 1, error_message = NULL
                WHERE task_type = 'graph_mutation' AND id = ? AND claimed_at IS NULL AND available_at <= ?
                  AND status IN ('pending', 'graph_committed')
                """)) {
            setInstant(statement, 1, now);
            statement.setString(2, requestId);
            setInstant(statement, 3, now);
            return statement.executeUpdate() == 1;
        }
    }

    private Optional<KnowledgeGraphMutationJob> findById(
            Connection connection,
            String requestId,
            boolean forUpdate
    ) throws SQLException {
        String sql = "SELECT * FROM knowledge_tasks WHERE task_type = 'graph_mutation' AND id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private KnowledgeGraphMutationJob map(ResultSet resultSet) throws SQLException {
        String payload = resultSet.getString("canonical_payload");
        return new KnowledgeGraphMutationJob(
                codec.decode(payload), resultSet.getString("tenant_id"),
                resultSet.getString("source_revision_id"),
                resultSet.getString("payload_hash"), payload,
                KnowledgeGraphMutationJob.Status.valueOf(
                        resultSet.getString("status").toUpperCase(java.util.Locale.ROOT)),
                resultSet.getInt("attempts"), instant(resultSet, "available_at"),
                instant(resultSet, "claimed_at"),
                (Integer) resultSet.getObject("graph_node_count"),
                (Integer) resultSet.getObject("graph_relation_count"),
                instant(resultSet, "graph_committed_at"),
                instant(resultSet, "completed_at"), resultSet.getString("error_message"),
                instant(resultSet, "created_at"));
    }

    private void updateClaimed(
            String requestId,
            String sql,
            Instant firstInstant,
            String errorMessage
    ) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setInstant(statement, 1, firstInstant);
            statement.setString(2, boundedError(errorMessage));
            statement.setString(3, required(requestId));
            requireOne(statement.executeUpdate(), "Graph mutation is not claimed");
        } catch (SQLException exception) {
            throw persistence("Failed to update graph mutation " + requestId, exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) statement.setTimestamp(index, null);
        else statement.setTimestamp(index, Timestamp.from(value));
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) throw new KnowledgePersistenceException(message);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String boundedError(String value) {
        String normalized = value == null || value.isBlank() ? "Unknown failure" : value;
        return normalized.length() <= 1024 ? normalized : normalized.substring(0, 1024);
    }

    private static KnowledgePersistenceException persistence(String message, Exception cause) {
        return new KnowledgePersistenceException(message, cause);
    }

    private static void rollback(Connection connection, Exception original) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void close(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The primary operation has already completed or failed.
        }
    }
}
