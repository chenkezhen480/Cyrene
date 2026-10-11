package com.harness.agent.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.*;
import com.harness.core.model.CancellationToken;
import com.harness.core.persistence.SqlConnectionProvider;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlSubAgentTaskRepositoryTest {
    @Test void terminalResultAndEventAreOneAtomicUpdateAndRollbackOnFailure() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLException("database unavailable"));
        var repository = new MysqlSubAgentTaskRepository(() -> connection, new ObjectMapper(), Duration.ofDays(1));
        var result = SubAgentResult.failure("task", "failed", 1, false);
        assertThatThrownBy(() -> repository.complete("task", SubAgentStatus.FAILED, result)).isInstanceOf(IllegalStateException.class);
        verify(connection).setAutoCommit(false);
        verify(statement).setString(4, "subagent:task");
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void persistenceFailureCannotPublishTerminalStatusOrCompleteFuture() {
        SubAgentTaskRepository repository = mock(SubAgentTaskRepository.class);
        when(repository.complete(anyString(), any(), any())).thenThrow(new IllegalStateException("offline"));
        var task = SubAgentTask.create("task", "task", null, null, null, List.of(), List.of(), null);
        var record = new SubAgentTaskRecord("task", "run", "session", "turn", task, new CancellationToken(),
                new AgentRunContext.Owner("user", "tenant", "business"), "call", "trace", repository);
        assertThatThrownBy(() -> record.fail(SubAgentResult.failure("task", "failed", 0, false))).hasMessage("offline");
        assertThat(record.completion()).isNotDone();
        assertThat(record.status().get()).isEqualTo(SubAgentStatus.QUEUED);
    }
}
