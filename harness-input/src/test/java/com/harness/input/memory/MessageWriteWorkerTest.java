package com.harness.input.memory;

import com.harness.core.model.MessageBlock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageWriteWorkerTest {

    @Test
    void awaitTrace_commitsAllQueuedRequestMessagesInOneBatch() {
        MessageStore store = mock(MessageStore.class);
        when(store.saveBatch(anyList())).thenReturn(List.of(101L, 102L, 103L));
        MessageWriteWorker worker = new MessageWriteWorker(store, 1, 0);

        var user = worker.submit("session-1", "trace-1", "user", blocks("Q"), false);
        var tool = worker.submit("session-1", "trace-1", "tool", blocks("evidence"), false);
        var assistant = worker.submit(
                "session-1", "trace-1", "assistant", blocks("A"), false);

        worker.awaitTrace("trace-1");

        ArgumentCaptor<List<MessageWrite>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).saveBatch(captor.capture());
        assertThat(captor.getValue()).extracting(MessageWrite::role)
                .containsExactly("user", "tool", "assistant");
        assertThat(captor.getValue()).extracting(MessageWrite::traceId)
                .containsOnly("trace-1");
        assertThat(user.join()).isEqualTo(101L);
        assertThat(tool.join()).isEqualTo(102L);
        assertThat(assistant.join()).isEqualTo(103L);
    }

    @Test
    void transactionFailureFailsEveryWriteAndNeverFallsBackToIndividualSave() {
        MessageStore store = mock(MessageStore.class);
        when(store.saveBatch(anyList())).thenThrow(new MemoryStoreException("database down"));
        MessageWriteWorker worker = new MessageWriteWorker(store, 2, 0);
        var first = worker.submit("session-1", "trace-1", "user", blocks("Q"), false);
        var second = worker.submit("session-1", "trace-1", "assistant", blocks("A"), false);

        assertThatThrownBy(() -> worker.awaitTrace("trace-1"))
                .isInstanceOf(MemoryStoreException.class)
                .hasMessageContaining("root Trace trace-1");

        verify(store, times(2)).saveBatch(anyList());
        verify(store, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(first.isCompletedExceptionally()).isTrue();
        assertThat(second.isCompletedExceptionally()).isTrue();
        assertThat(worker.getDeadLetterQueue()).hasSize(2);
    }

    private static List<MessageBlock> blocks(String text) {
        return List.of(new MessageBlock(MessageBlock.BlockType.TEXT, text, null));
    }
}
