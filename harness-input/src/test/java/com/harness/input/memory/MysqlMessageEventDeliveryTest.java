package com.harness.input.memory;

import com.harness.core.model.MessageBlock;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlMessageEventDeliveryTest {
    @Test void recoveryReplyAndCompletionMarkerRollbackTogether() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet keys = mock(ResultSet.class);
        when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.next()).thenReturn(true);
        when(keys.getLong(1)).thenReturn(7L);
        when(statement.executeUpdate()).thenReturn(1).thenThrow(new SQLException("marker failure"));
        var store = new MysqlMessageStore(() -> connection);
        var message = new MessageWrite("session", "trace", "assistant", List.of(new MessageBlock(MessageBlock.BlockType.TEXT, "done", null)), false);
        var marker = new MessageWrite("session", "trace", "subagent_resume_done", List.of(), false);
        assertThatThrownBy(() -> store.appendOnceBatch(List.of(new MessageStore.EventMessage(message, "reply"),
                new MessageStore.EventMessage(marker, "marker")))).isInstanceOf(MemoryStoreException.class);
        verify(connection).rollback();
        verify(statement).setString(4, "[]");
        verify(connection, never()).commit();
    }

    @Test void disabledStoreReportsUnsupportedDelivery() {
        var store = new NoOpMessageStore();
        var message = new MessageWrite("session", "trace", "subagent_event", List.of(), false);
        assertThatThrownBy(() -> store.appendOnce(message, "event")).isInstanceOf(UnsupportedOperationException.class);
    }
}
