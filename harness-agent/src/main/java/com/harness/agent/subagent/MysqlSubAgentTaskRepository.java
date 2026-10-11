package com.harness.agent.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.*;
import com.harness.core.model.PageResponse;
import com.harness.core.persistence.SqlConnectionProvider;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** MySQL owns completion and delivery; every lease acknowledgement is fenced by its token. */
public final class MysqlSubAgentTaskRepository implements SubAgentTaskRepository {
    private static final String TERMINAL = "'SUCCEEDED','INCOMPLETE','FAILED','CANCELLED','TIMED_OUT','INTERRUPTED'";
    private static final String COLUMNS = "task_id,tenant_id,owner_user_id,owner_identity,owner_session_id,owner_run_id,owner_turn_id,root_trace_id,spawn_tool_call_id,task_json,status,result_json,delivery_state,delivery_event_id,created_at,expires_at";
    private final SqlConnectionProvider connections;
    private final ObjectMapper mapper;
    private final Duration retention;

    public MysqlSubAgentTaskRepository(SqlConnectionProvider connections, ObjectMapper mapper, Duration retention) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy().findAndRegisterModules();
        if (retention == null || retention.isZero() || retention.isNegative()) throw new IllegalArgumentException("retention must be positive");
        this.retention = retention;
    }

    @Override public void create(SubAgentTaskRecord record) {
        String tenant = SubAgentTaskRepository.tenant(record.owner());
        requireOwnerSession(record.owner(), record.ownerSessionId());
        update("INSERT INTO subagent_tasks (task_id,tenant_id,owner_user_id,owner_identity,owner_session_id,owner_run_id,owner_turn_id,root_trace_id,spawn_tool_call_id,task_json,status,delivery_state,created_at,updated_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS JSON),'QUEUED','INLINE_PENDING',?,?,TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3)))", statement -> {
            statement.setString(1, record.taskId()); statement.setString(2, tenant);
            statement.setString(3, record.owner().userId()); statement.setString(4, record.owner().identity());
            statement.setString(5, record.ownerSessionId()); statement.setString(6, record.ownerRunId());
            statement.setString(7, record.ownerTurnId()); statement.setString(8, record.rootTraceId());
            statement.setString(9, record.spawnToolCallId()); statement.setString(10, json(record.task()));
            statement.setTimestamp(11, Timestamp.from(record.createdAt())); statement.setTimestamp(12, Timestamp.from(record.createdAt()));
            statement.setLong(13, intervalMicros(retention));
        });
    }

    @Override public boolean transition(String taskId, SubAgentStatus expected, SubAgentStatus next) {
        if (expected.isTerminal() || next.isTerminal()) throw new IllegalArgumentException("Use complete for terminal status");
        return update("UPDATE subagent_tasks SET status=?,updated_at=CURRENT_TIMESTAMP(3) WHERE task_id=BINARY ? AND status=?", statement -> {
            statement.setString(1, next.name()); statement.setString(2, taskId); statement.setString(3, expected.name());
        }) == 1;
    }

    @Override public boolean complete(String taskId, SubAgentStatus status, SubAgentResult result) {
        if (!status.isTerminal() || result.status() != status || !taskId.equals(result.taskId())) throw new IllegalArgumentException("Invalid terminal result");
        return transaction(connection -> complete(connection, taskId, status, result));
    }

    private boolean complete(Connection connection, String taskId, SubAgentStatus status, SubAgentResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE subagent_tasks SET status=?,result_json=CAST(? AS JSON),child_trace_id=?,delivery_event_id=?,updated_at=CURRENT_TIMESTAMP(3),finished_at=CURRENT_TIMESTAMP(3),expires_at=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3)) WHERE task_id=BINARY ? AND status NOT IN (" + TERMINAL + ")")) {
            statement.setString(1, status.name()); statement.setString(2, json(result));
            statement.setString(3, result.traceId()); statement.setString(4, "subagent:" + taskId);
            statement.setLong(5, intervalMicros(retention)); statement.setString(6, taskId);
            return statement.executeUpdate() == 1;
        }
    }

    @Override public boolean changeDelivery(String taskId, ResultDeliveryState expected, ResultDeliveryState next) {
        return update("UPDATE subagent_tasks SET delivery_state=?,updated_at=CURRENT_TIMESTAMP(3) WHERE task_id=BINARY ? AND delivery_state=?"
                + (next == ResultDeliveryState.INLINE_CONSUMED ? " AND result_json IS NOT NULL" : ""), statement -> {
            statement.setString(1, next.name()); statement.setString(2, taskId); statement.setString(3, expected.name());
        }) == 1;
    }

    @Override public Optional<StoredTask> findAuthorized(AgentRunContext.Owner owner, String sessionId, String taskId) {
        var rows = query("SELECT " + COLUMNS + " FROM subagent_tasks WHERE tenant_id=BINARY ? AND owner_user_id <=> BINARY ? AND owner_session_id=BINARY ? AND task_id=BINARY ? AND expires_at>CURRENT_TIMESTAMP(3)", statement -> {
            bindOwner(statement, owner, sessionId); statement.setString(4, taskId);
        }, this::readTask);
        return rows.stream().findFirst();
    }

    @Override public PageResponse<StoredTask> listAuthorized(AgentRunContext.Owner owner, String sessionId, String cursor, int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        String[] after = SubAgentTaskRepository.decodeCursor(cursor);
        var rows = query("SELECT " + COLUMNS + " FROM subagent_tasks WHERE tenant_id=BINARY ? AND owner_user_id <=> BINARY ? AND owner_session_id=BINARY ? AND expires_at>CURRENT_TIMESTAMP(3)"
                + (after == null ? "" : " AND (created_at>? OR (created_at=? AND task_id>BINARY ?))")
                + " ORDER BY created_at,task_id LIMIT ?", statement -> {
            bindOwner(statement, owner, sessionId);
            int index = 4;
            if (after != null) {
                Timestamp timestamp = new Timestamp(Long.parseLong(after[0]));
                statement.setTimestamp(index++, timestamp); statement.setTimestamp(index++, timestamp); statement.setString(index++, after[1]);
            }
            statement.setInt(index, limit + 1);
        }, this::readTask);
        return PageResponse.fromFetched(rows, limit, SubAgentTaskRepository::cursor);
    }

    @Override public List<SessionInbox.SubAgentCompletedEvent> claimDeliveries(String sessionId, Duration lease, int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        if (lease.isNegative() || lease.isZero()) throw new IllegalArgumentException("lease must be positive");
        return transaction(connection -> {
            List<StoredTask> tasks = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM subagent_tasks WHERE owner_session_id=BINARY ? AND result_json IS NOT NULL AND expires_at>CURRENT_TIMESTAMP(3) AND (delivery_state='DETACHED' OR (delivery_state='DELIVERY_CLAIMED' AND delivery_lease_until<CURRENT_TIMESTAMP(3))) ORDER BY created_at,task_id LIMIT ? FOR UPDATE SKIP LOCKED")) {
                statement.setString(1, sessionId); statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) tasks.add(readTask(rows)); }
            }
            var events = new ArrayList<SessionInbox.SubAgentCompletedEvent>();
            try (PreparedStatement statement = connection.prepareStatement("UPDATE subagent_tasks SET delivery_state='DELIVERY_CLAIMED',delivery_lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3)),delivery_lease_token=?,updated_at=CURRENT_TIMESTAMP(3) WHERE task_id=BINARY ?")) {
                for (var task : tasks) {
                    String token = UUID.randomUUID().toString();
                    statement.setLong(1, intervalMicros(lease));
                    statement.setString(2, token); statement.setString(3, task.taskId());
                    if (statement.executeUpdate() != 1) throw new SQLException("Delivery claim update failed");
                    events.add(task.event(token));
                }
            }
            return List.copyOf(events);
        });
    }

    @Override public void acknowledgeDelivery(String eventId, String leaseToken) { finishDelivery(eventId, leaseToken, "SESSION_RESUMED"); }
    @Override public void releaseDelivery(String eventId, String leaseToken) { finishDelivery(eventId, leaseToken, "DETACHED"); }
    private void finishDelivery(String eventId, String leaseToken, String next) {
        update("UPDATE subagent_tasks SET delivery_state=?,delivery_lease_until=NULL,delivery_lease_token=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE delivery_event_id=BINARY ? AND delivery_lease_token=BINARY ? AND delivery_state='DELIVERY_CLAIMED'", statement -> {
            statement.setString(1, next); statement.setString(2, eventId); statement.setString(3, leaseToken);
        });
    }

    @Override public void renewDelivery(String eventId, String leaseToken, Duration lease) {
        update("UPDATE subagent_tasks SET delivery_lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(3)) WHERE delivery_event_id=BINARY ? AND delivery_lease_token=BINARY ? AND delivery_state='DELIVERY_CLAIMED'", statement -> {
            statement.setLong(1, intervalMicros(lease)); statement.setString(2, eventId); statement.setString(3, leaseToken);
        });
    }

    private static long intervalMicros(Duration duration) {
        long micros = Math.multiplyExact(duration.toMillis(), 1000);
        if (micros < 1) throw new IllegalArgumentException("Delivery interval must be positive");
        return micros;
    }

    @Override public PageResponse<String> pendingSessions(String cursor, int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        var sessions = query("SELECT DISTINCT owner_session_id FROM subagent_tasks WHERE owner_session_id IS NOT NULL AND result_json IS NOT NULL AND expires_at>CURRENT_TIMESTAMP(3) AND (delivery_state='DETACHED' OR (delivery_state='DELIVERY_CLAIMED' AND delivery_lease_until<CURRENT_TIMESTAMP(3))) AND owner_session_id>BINARY ? ORDER BY owner_session_id LIMIT ?", statement -> {
            statement.setString(1, cursor == null ? "" : cursor); statement.setInt(2, limit + 1);
        }, rows -> rows.getString(1));
        return PageResponse.fromFetched(sessions, limit, id -> id);
    }

    @Override public int suppressSession(String sessionId) {
        return update("UPDATE subagent_tasks SET delivery_state='SUPPRESSED',delivery_lease_until=NULL,delivery_lease_token=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE owner_session_id=BINARY ? AND delivery_state NOT IN ('INLINE_CONSUMED','SESSION_RESUMED','SUPPRESSED')", statement -> statement.setString(1, sessionId));
    }

    @Override public boolean hasPendingDelivery(String sessionId) {
        return !query("SELECT task_id FROM subagent_tasks WHERE owner_session_id=BINARY ? AND result_json IS NOT NULL AND expires_at>CURRENT_TIMESTAMP(3) AND (delivery_state='DETACHED' OR (delivery_state='DELIVERY_CLAIMED' AND delivery_lease_until<CURRENT_TIMESTAMP(3))) LIMIT 1", statement -> statement.setString(1, sessionId), rows -> rows.getString(1)).isEmpty();
    }

    @Override public void suppressTask(String taskId) {
        update("UPDATE subagent_tasks SET delivery_state='SUPPRESSED',delivery_lease_token=NULL,delivery_lease_until=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE task_id=BINARY ? AND delivery_state NOT IN ('INLINE_CONSUMED','SESSION_RESUMED','SUPPRESSED')",
                statement -> statement.setString(1, taskId));
    }

    @Override public int interruptUnfinished(int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        return transaction(connection -> {
            List<StoredTask> tasks = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM subagent_tasks WHERE status NOT IN (" + TERMINAL + ") OR delivery_state='INLINE_PENDING' ORDER BY created_at,task_id LIMIT ? FOR UPDATE")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) tasks.add(readTask(rows)); }
            }
            for (var task : tasks) {
                if (!task.status().isTerminal()) {
                    var result = new SubAgentResult(task.taskId(), null, "Task interrupted by process restart", false, SubAgentStatus.INTERRUPTED,
                            List.of(), ToolExecutionSummary.empty(), ContractValidation.notEvaluated(task.task().completionContract() != null), null, 0, null);
                    complete(connection, task.taskId(), SubAgentStatus.INTERRUPTED, result);
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE subagent_tasks SET delivery_state='DETACHED',updated_at=CURRENT_TIMESTAMP(3) WHERE task_id=BINARY ? AND delivery_state='INLINE_PENDING'")) {
                    statement.setString(1, task.taskId()); statement.executeUpdate();
                }
            }
            return tasks.size();
        });
    }

    @Override public int deleteExpired(int limit) {
        SubAgentTaskRepository.requireLimit(limit);
        return update("DELETE FROM subagent_tasks WHERE status IN (" + TERMINAL + ") AND expires_at<CURRENT_TIMESTAMP(3) ORDER BY expires_at,task_id LIMIT ?", statement -> statement.setInt(1, limit));
    }

    private void bindOwner(PreparedStatement statement, AgentRunContext.Owner owner, String sessionId) throws SQLException {
        requireOwnerSession(owner, sessionId);
        statement.setString(1, SubAgentTaskRepository.tenant(owner)); statement.setString(2, owner.userId()); statement.setString(3, sessionId);
    }

    private static void requireOwnerSession(AgentRunContext.Owner owner, String sessionId) {
        if (owner == null || owner.userId() == null || owner.userId().isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Durable tasks require a trusted user and owned session");
        }
    }

    private StoredTask readTask(ResultSet rows) throws SQLException {
        try {
            String resultJson = rows.getString("result_json");
            return new StoredTask(rows.getString("task_id"), rows.getString("owner_run_id"), rows.getString("owner_session_id"), rows.getString("owner_turn_id"),
                    new AgentRunContext.Owner(rows.getString("owner_user_id"), rows.getString("tenant_id"), rows.getString("owner_identity")),
                    rows.getString("spawn_tool_call_id"), rows.getString("root_trace_id"), mapper.readValue(rows.getString("task_json"), SubAgentTask.class),
                    SubAgentStatus.valueOf(rows.getString("status")), resultJson == null ? null : mapper.readValue(resultJson, SubAgentResult.class),
                    ResultDeliveryState.valueOf(rows.getString("delivery_state")), rows.getString("delivery_event_id"),
                    rows.getTimestamp("created_at").toInstant(), rows.getTimestamp("expires_at").toInstant());
        } catch (JsonProcessingException e) { throw new SQLException("Invalid persisted task JSON", e); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize sub-agent task", e); }
    }

    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface private interface Reader<T> { T read(ResultSet rows) throws SQLException; }
    @FunctionalInterface private interface Transaction<T> { T run(Connection connection) throws SQLException; }
    private int update(String sql, Binder binder) {
        try (Connection connection = connections.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement); return statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Sub-agent task persistence failed", e); }
    }
    private <T> List<T> query(String sql, Binder binder, Reader<T> reader) {
        try (Connection connection = connections.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                var values = new ArrayList<T>(); while (rows.next()) values.add(reader.read(rows)); return List.copyOf(values);
            }
        } catch (SQLException e) { throw new IllegalStateException("Sub-agent task query failed", e); }
    }
    private <T> T transaction(Transaction<T> transaction) {
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = transaction.run(connection); connection.commit(); return result;
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw new IllegalStateException("Sub-agent task transaction failed", e); }
    }
}
