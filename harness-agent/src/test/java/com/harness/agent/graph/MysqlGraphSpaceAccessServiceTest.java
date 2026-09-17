package com.harness.agent.graph;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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

    /** A schema-scoped cleanup must also reach bindings of spaces that hold no graph nodes. */
    @Test
    void deletesEveryBindingOfASchemaInOneStatement() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(3);

        var service = new MysqlGraphSpaceAccessService(() -> connection);
        int deleted = service.deleteBindingsBySchema("schema-1");

        assertThat(deleted).isEqualTo(3);
        verify(connection).setAutoCommit(false);
        // Which column the statement filters on is the whole point of this method: a graph_id
        // predicate here would wipe one graph's bindings instead of the Schema's.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("WHERE schema_id = ?")
                .doesNotContain("graph_id");
        verify(statement).setString(1, "schema-1");
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    /**
     * A freshly built space must be reachable by the tenant that built it: without this row the space
     * is invisible to every tenant once the table exists.
     */
    @Test
    void registersAWriteBindingForTheCreatingTenant() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        var service = new MysqlGraphSpaceAccessService(() -> connection);
        service.registerBinding("000000", "graph-1", "schema-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO graph_space_bindings")
                .contains("'write'");
        // An operator's narrower permission or disabled row must win over the automatic grant.
        assertThat(sql.getValue()).contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql.getValue()).doesNotContain("permission = 'write'");
        verify(statement).setString(1, "000000");
        verify(statement).setString(2, "graph-1");
        verify(statement).setString(3, "schema-1");
    }

    /**
     * The framework rule is "missing tenant uses 000000", so an unscoped caller — the console sends
     * only userId — must read the default tenant's bindings instead of failing the request.
     */
    @Test
    void treatsAMissingTenantAsTheDefaultTenant() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        var service = new MysqlGraphSpaceAccessService(() -> connection);
        service.listReadable(null, 10, "");

        verify(statement).setString(1, "000000");
        assertThatThrownBy(() -> service.requireReadable("  ", "graph-1", "schema-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not readable");
        verify(statement, org.mockito.Mockito.times(2)).setString(1, "000000");
    }

    @Test
    void rollsBackAndReportsWhenABindingDeleteFails() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new java.sql.SQLException("deadlock"));

        var service = new MysqlGraphSpaceAccessService(() -> connection);

        assertThatThrownBy(() -> service.deleteBindingsBySchema("schema-1"))
                .isInstanceOf(GraphSpaceAccessException.class)
                .hasMessageContaining("Failed to delete graph-space bindings");
        verify(connection).rollback();
        verify(connection, org.mockito.Mockito.never()).commit();
        verify(connection).setAutoCommit(true);
    }
}
