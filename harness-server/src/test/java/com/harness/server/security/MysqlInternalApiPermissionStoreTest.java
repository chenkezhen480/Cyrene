package com.harness.server.security;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlInternalApiPermissionStoreTest {
    @Test
    void failedBatchRollsBackAndPreservesRollbackFailure() throws Exception {
        var connection = mock(Connection.class);
        var delete = mock(PreparedStatement.class);
        var insert = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(delete, insert);
        var failure = new SQLException("Batch failed");
        var rollbackFailure = new SQLException("Rollback failed");
        when(insert.executeBatch()).thenThrow(failure);
        doThrow(rollbackFailure).when(connection).rollback();
        var store = new MysqlInternalApiPermissionStore(() -> connection);

        assertThatThrownBy(() -> store.replace("t1", "reader", Set.of("trace.read")))
                .isInstanceOf(MysqlInternalApiPermissionStore.PermissionStoreException.class)
                .hasCause(failure);
        assertThat(failure.getSuppressed()).containsExactly(rollbackFailure);
        verify(connection).setAutoCommit(false);
        verify(delete).executeUpdate();
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }
}
