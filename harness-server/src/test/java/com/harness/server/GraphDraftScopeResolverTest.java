package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.Session;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.memory.SessionStore;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphDraftScopeResolverTest {
    @Test void bindsVerifiedOwnerAndServerIdsAndRejectsForeignSession() {
        var sessions = mock(SessionStore.class);
        var context = mock(Context.class);
        when(context.body()).thenReturn("{\"sessionId\":\"session\",\"runId\":\"forged\",\"traceId\":\"forged\"}");
        when(context.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE)).thenReturn(
                new RequestPrincipal("user", "tenant", "USER", RequestPrincipal.AuthenticationType.JWT));
        when(sessions.findByIdAndOwner("session", "user", "tenant")).thenReturn(Optional.of(mock(Session.class)));
        var resolver = new GraphDraftScopeResolver(sessions, new ObjectMapper());
        var scope = resolver.apply(context);
        assertThat(scope.userId()).isEqualTo("user");
        assertThat(scope.tenantId()).isEqualTo("tenant");
        assertThat(scope.sessionId()).isEqualTo("session");
        assertThat(scope.runId()).isNotEqualTo("forged");
        assertThat(scope.traceId()).isNotEqualTo("forged");
        when(sessions.findByIdAndOwner("session", "user", "tenant")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.apply(context)).isInstanceOf(SecurityException.class);
    }
}
