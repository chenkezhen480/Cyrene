package com.harness.agent.graph;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlGraphSpaceAccessServiceTest {

    @Test
    void usesKeysetPaginationAndReturnsAnOpaqueCursor() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getLong("id")).thenReturn(11L, 12L, 13L);
        when(resultSet.getString("graph_id")).thenReturn("graph-1", "graph-2", "graph-3");
        when(resultSet.getString("schema_id")).thenReturn("schema-1", "schema-2", "schema-3");
        when(resultSet.getString("description")).thenReturn("学生能力", "设备关系", "组织架构");

        var service = new MysqlGraphSpaceAccessService(() -> connection);
        var page = service.listReadable("tenant-1", 2, "");

        assertThat(page.items()).containsExactly(
                new GraphSpaceReference("graph-1", "schema-1", "学生能力"),
                new GraphSpaceReference("graph-2", "schema-2", "设备关系")
        );
        assertThat(page.pageInfo().hasMore()).isTrue();
        assertThat(page.pageInfo().nextCursor()).isNotBlank();
        verify(statement).setString(1, "tenant-1");
        verify(statement).setLong(2, 0L);
        verify(statement).setInt(3, 3);
    }

    @Test
    void deletesBindingsInATransaction() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(2);

        var service = new MysqlGraphSpaceAccessService(() -> connection);
        int deleted = service.deleteBindings("graph-1", "schema-1");

        assertThat(deleted).isEqualTo(2);
        verify(connection).setAutoCommit(false);
        verify(statement).setString(1, "graph-1");
        verify(statement).setString(2, "schema-1");
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }
}
