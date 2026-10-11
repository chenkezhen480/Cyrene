package com.harness.server;

import com.harness.agent.SubAgentManager;
import com.harness.core.model.CancellationToken;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatCancellationHandlerTest {
    @Test void cancelsDetachedTasksAfterTheMainStreamHasEnded() {
        var manager = mock(SubAgentManager.class);
        var context = context();
        when(manager.cancelSession("session")).thenReturn(true);
        new ChatCancellationHandler(Map.of(), manager).cancel(context);
        verify(manager).cancelSession("session");
        verify(context).json(Map.of("status", "cancelled", "sessionId", "session"));
        verify(context, never()).status(404);
    }

    @Test void stopsTheLiveRequestEvenWhenDurableCancellationFails() {
        var manager = mock(SubAgentManager.class);
        var context = context();
        var token = new CancellationToken();
        when(manager.cancelSession("session")).thenThrow(new IllegalStateException("database unavailable"));
        new ChatCancellationHandler(Map.of("session", token), manager).cancel(context);
        assertThat(token.isCancelled()).isTrue();
        verify(context).status(500);
    }

    @Test void endedRunReturnsTheSpecificNoActiveRunError() {
        var context = context();
        new ChatCancellationHandler(Map.of(), mock(SubAgentManager.class)).cancel(context);
        verify(context).status(404);
        verify(context).json(argThat(value -> value instanceof com.harness.server.api.ApiError error
                && error.code() == com.harness.server.api.ApiErrorCode.CHAT_RUN_NOT_ACTIVE));
    }

    private Context context() {
        var context = mock(Context.class);
        when(context.pathParam("sessionId")).thenReturn("session");
        when(context.status(anyInt())).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        return context;
    }
}
