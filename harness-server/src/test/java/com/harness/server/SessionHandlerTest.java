package com.harness.server;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.PageResponse;
import com.harness.core.model.Session;
import com.harness.preprocess.memory.MessageStore;
import com.harness.preprocess.memory.MessageWriteWorker;
import com.harness.preprocess.memory.SessionMessageCache;
import com.harness.preprocess.memory.SessionStore;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionHandlerTest {

    @Test
    void list_returnsUnifiedPageResponse() {
        SessionStore sessionStore = mock(SessionStore.class);
        MessageStore messageStore = mock(MessageStore.class);
        Context context = mock(Context.class);
        List<Session> fetched = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(index -> session(
                        "session-" + index,
                        Instant.parse("2026-07-30T00:00:00Z")
                                .minusSeconds(index)))
                .toList();

        when(context.queryParam("userId")).thenReturn("user-1");
        when(context.queryParam("status")).thenReturn(null);
        when(context.queryParam("limit")).thenReturn(null);
        when(context.queryParam("cursor")).thenReturn(null);
        when(context.json(any())).thenReturn(context);
        when(sessionStore.findAll("user-1", null, null, 21)).thenReturn(fetched);

        SessionHandler handler = new SessionHandler(sessionStore, messageStore, null, null);
        handler.list(context);

        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        PageResponse<?> response = (PageResponse<?>) responseCaptor.getValue();
        assertThat(response.items()).hasSize(20);
        assertThat(response.pageInfo().limit()).isEqualTo(20);
        assertThat(response.pageInfo().hasMore()).isTrue();
        assertThat(response.pageInfo().nextCursor())
                .isEqualTo("2026-07-29T23:59:40Z");
    }

    @Test
    void messages_descendingPage_dropsLookaheadAndUsesOldestReturnedIdAsCursor() {
        SessionStore sessionStore = mock(SessionStore.class);
        MessageStore messageStore = mock(MessageStore.class);
        SessionMessageCache cache = mock(SessionMessageCache.class);
        MessageWriteWorker messageWriteWorker = mock(MessageWriteWorker.class);
        Context context = mock(Context.class);

        when(context.pathParam("sessionId")).thenReturn("session-1");
        when(context.queryParam("limit")).thenReturn("2");
        when(context.queryParam("cursor")).thenReturn(null);
        when(context.queryParam("direction")).thenReturn("desc");
        when(context.json(any())).thenReturn(context);
        when(sessionStore.findById("session-1")).thenReturn(Optional.of(session("session-1")));
        when(messageStore.loadPage("session-1", 0, 3, false)).thenReturn(List.of(
                message(98), message(99), message(100)));

        SessionHandler handler = new SessionHandler(
                sessionStore, messageStore, cache, messageWriteWorker);
        handler.messages(context);

        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        PageResponse<?> response = (PageResponse<?>) responseCaptor.getValue();

        assertThat(response.items())
                .extracting(item -> ((MemoryMessage) item).id())
                .containsExactly(99L, 100L);
        assertThat(response.pageInfo().nextCursor()).isEqualTo("99");
        assertThat(response.pageInfo().hasMore()).isTrue();
    }

    @Test
    void messages_ascendingPage_keepsFirstItemsAndUsesNewestReturnedIdAsCursor() {
        SessionStore sessionStore = mock(SessionStore.class);
        MessageStore messageStore = mock(MessageStore.class);
        Context context = mock(Context.class);

        when(context.pathParam("sessionId")).thenReturn("session-1");
        when(context.queryParam("limit")).thenReturn("2");
        when(context.queryParam("cursor")).thenReturn(null);
        when(context.queryParam("direction")).thenReturn("asc");
        when(context.json(any())).thenReturn(context);
        when(sessionStore.findById("session-1")).thenReturn(Optional.of(session("session-1")));
        when(messageStore.loadPage("session-1", 0, 3, true)).thenReturn(List.of(
                message(1), message(2), message(3)));

        SessionHandler handler = new SessionHandler(sessionStore, messageStore, null, null);
        handler.messages(context);

        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).json(responseCaptor.capture());
        PageResponse<?> response = (PageResponse<?>) responseCaptor.getValue();

        assertThat(response.items())
                .extracting(item -> ((MemoryMessage) item).id())
                .containsExactly(1L, 2L);
        assertThat(response.pageInfo().nextCursor()).isEqualTo("2");
        assertThat(response.pageInfo().hasMore()).isTrue();
    }

    private static Session session(String sessionId) {
        return session(sessionId, Instant.parse("2026-07-30T00:00:00Z"));
    }

    private static Session session(String sessionId, Instant lastActive) {
        Instant createdAt = Instant.parse("2026-07-30T00:00:00Z");
        return new Session(
                sessionId,
                "user-1",
                null,
                createdAt,
                lastActive,
                null,
                Session.SessionStatus.active);
    }

    private static MemoryMessage message(long id) {
        return new MemoryMessage(id, "session-1", "assistant", List.of(), false,
                Instant.parse("2026-07-30T00:00:00Z"));
    }
}
