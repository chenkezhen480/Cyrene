package com.harness.server;

import com.harness.agent.AgentRunContext;
import com.harness.agent.SubAgentManager;
import com.harness.core.model.PageInfo;
import com.harness.core.model.PageResponse;
import com.harness.core.model.Session;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.memory.SessionStore;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionTaskHandlerTest {
    @Test void returnsScopedPageAndRejectsRequestedOwnerSpoofing() {
        var tasks = mock(SubAgentManager.class);
        var sessions = mock(SessionStore.class);
        var context = context();
        when(sessions.findByIdAndOwner("session", "user", "tenant")).thenReturn(Optional.of(mock(Session.class)));
        PageResponse<com.harness.agent.subagent.SubAgentTaskRepository.StoredTask> page =
                new PageResponse<>(List.of(), new PageInfo(20, "next", true));
        when(tasks.listTasks(new AgentRunContext.Owner("user", "tenant", "USER"), "session", "cursor", 20))
                .thenReturn(page);
        new SessionTaskHandler(tasks, sessions).list(context);
        verify(context).json(page);

        when(context.queryParam("userId")).thenReturn("another-user");
        new SessionTaskHandler(tasks, sessions).list(context);
        verify(context).status(403);
        verify(tasks, times(1)).listTasks(any(), any(), any(), anyInt());
    }

    @Test void missingOwnedSessionDoesNotExposeTaskExistence() {
        var tasks = mock(SubAgentManager.class);
        var sessions = mock(SessionStore.class);
        var context = context();
        when(sessions.findByIdAndOwner("session", "user", "tenant")).thenReturn(Optional.empty());
        new SessionTaskHandler(tasks, sessions).list(context);
        verify(context).status(404);
        verifyNoInteractions(tasks);
    }

    private Context context() {
        var context = mock(Context.class);
        when(context.pathParam("sessionId")).thenReturn("session");
        when(context.queryParam("cursor")).thenReturn("cursor");
        when(context.queryParam("limit")).thenReturn("20");
        when(context.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE)).thenReturn(
                new RequestPrincipal("user", "tenant", "USER", RequestPrincipal.AuthenticationType.JWT));
        when(context.status(anyInt())).thenReturn(context);
        when(context.json(any())).thenReturn(context);
        return context;
    }
}
