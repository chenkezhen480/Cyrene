package com.harness.server.security;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.Session;
import com.harness.core.model.ArtifactStore;
import com.harness.core.security.ApiEndpointDescriptor;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.memory.SessionStore;
import com.harness.trace.store.TraceStore;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.time.Instant;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalApiResourceAuthorizerTest {
    @Test
    void confirmationAndChatInputReferencesCannotBypassResourceAuthorization() throws Exception {
        var sessions = mock(SessionStore.class);
        var inputs = mock(com.harness.server.FileUploadHandler.class);
        var resources = new InternalApiResourceAuthorizer(sessions, mock(TraceStore.class), mock(ArtifactStore.class), inputs);
        var principal = new RequestPrincipal("user-1", "tenant-1", "reader", RequestPrincipal.AuthenticationType.JWT);
        var context = mock(Context.class);
        when(context.bodyAsClass(com.harness.server.ConfirmationHandler.ConfirmationActionRequest.class))
                .thenReturn(new com.harness.server.ConfirmationHandler.ConfirmationActionRequest("user-1", "foreign"));
        assertThatThrownBy(() -> resources.authorize(context, principal,
                new ApiEndpointDescriptor("confirmation.approve", "approve", "POST", "/api/confirmations/{requestId}/approve", "chat", USER)))
                .isInstanceOf(SecurityException.class);
        var request = new com.harness.server.ChatHandler.ChatRequest("read", null, null,
                java.util.Map.of("File", "/files/input/foreign.txt"), null, "TEXT");
        when(context.bodyAsClass(com.harness.server.ChatHandler.ChatRequest.class)).thenReturn(request);
        doThrow(new SecurityException("Foreign upload")).when(inputs).authorizeReference("/files/input/foreign.txt", principal);
        assertThatThrownBy(() -> resources.authorize(context, principal,
                new ApiEndpointDescriptor("chat.create", "chat", "POST", "/api/chat", "chat", USER)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void sessionChecksDoNotTrustCaseInsensitiveStoreMatches() {
        var sessions = mock(SessionStore.class);
        var resources = new InternalApiResourceAuthorizer(sessions, mock(TraceStore.class), mock(ArtifactStore.class));
        var principal = new RequestPrincipal("user-1", "tenant-1", "reader", RequestPrincipal.AuthenticationType.JWT);
        var context = mock(Context.class);
        when(context.pathParam("sessionId")).thenReturn("s1");
        when(sessions.findByIdAndOwner("s1", "user-1", "tenant-1")).thenReturn(Optional.of(new Session(
                "s1", "user-1", "TENANT-1", "", Instant.now(), Instant.now(), null, Session.SessionStatus.active)));
        var endpoint = new ApiEndpointDescriptor("session.read", "read", "GET", "/api/sessions/{sessionId}", "sessions", SESSION);
        assertThatThrownBy(() -> resources.authorize(context, principal, endpoint)).isInstanceOf(SecurityException.class);
    }
    @Test
    void knowledgeOfASessionOrTraceIdCannotCrossTheOwnerScope() {
        var sessions = mock(SessionStore.class);
        var traces = mock(TraceStore.class);
        var resources = new InternalApiResourceAuthorizer(sessions, traces, mock(ArtifactStore.class));
        var principal = new RequestPrincipal("user-1", "tenant-1", "reader", RequestPrincipal.AuthenticationType.JWT);
        var context = mock(Context.class);
        when(context.pathParam("sessionId")).thenReturn("foreign-session");
        when(sessions.findByIdAndOwner("foreign-session", "user-1", "tenant-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resources.authorize(context, principal,
                new ApiEndpointDescriptor("session.read", "read", "GET", "/api/sessions/{sessionId}", "sessions", SESSION)))
                .isInstanceOf(SecurityException.class);
        when(context.pathParam("id")).thenReturn("foreign-trace");
        when(context.pathParamMap()).thenReturn(java.util.Map.of("id", "foreign-trace"));
        when(traces.findById("foreign-trace")).thenReturn(Optional.of(AgentTrace.builder()
                .traceId("foreign-trace").userId("user-2").sessionId("foreign-session").build()));
        assertThatThrownBy(() -> resources.authorize(context, principal,
                new ApiEndpointDescriptor("trace.read", "read", "GET", "/api/trace/{id}", "traces", TRACE)))
                .isInstanceOf(SecurityException.class);
    }
}
