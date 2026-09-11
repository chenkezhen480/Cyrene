package com.harness.server;

import com.harness.core.model.AgentTrace;
import com.harness.core.model.Session;
import com.harness.input.memory.SessionStore;
import com.harness.trace.store.TraceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceFeedbackServiceTest {

    private TraceStore traceStore;
    private SessionStore sessionStore;
    private TraceFeedbackService service;

    @BeforeEach
    void setUp() {
        traceStore = mock(TraceStore.class);
        sessionStore = mock(SessionStore.class);
        service = new TraceFeedbackService(traceStore, sessionStore);
    }

    @Test
    void ownedTrace_updatesOnlyStableFeedbackMetadata() {
        AgentTrace trace = trace("trace-1", "session-1");
        var owner = new SessionRequestOwnerResolver.Owner("user-1", "tenant-1");
        when(traceStore.findById("trace-1")).thenReturn(Optional.of(trace));
        when(sessionStore.findByIdAndOwner("session-1", "user-1", "tenant-1"))
                .thenReturn(Optional.of(mock(Session.class)));
        when(traceStore.updateMetadata(
                "trace-1", java.util.Map.of("user_feedback", "positive")))
                .thenReturn(true);

        TraceFeedbackService.FeedbackResult result =
                service.update("trace-1", " POSITIVE ", owner);

        assertThat(result.feedback()).isEqualTo("positive");
        verify(traceStore).updateMetadata(
                "trace-1", java.util.Map.of("user_feedback", "positive"));
    }

    @Test
    void ownerMismatch_rejectsWithoutWriting() {
        AgentTrace trace = trace("trace-1", "session-1");
        var owner = new SessionRequestOwnerResolver.Owner("other-user", null);
        when(traceStore.findById("trace-1")).thenReturn(Optional.of(trace));
        when(sessionStore.findByIdAndOwner("session-1", "other-user", null))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("trace-1", "negative", owner))
                .isInstanceOf(TraceFeedbackService.TraceOwnershipException.class);
        verify(traceStore, never()).updateMetadata(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void unboundTraceAndInvalidValue_areRejectedExplicitly() {
        var owner = new SessionRequestOwnerResolver.Owner("user-1", null);
        when(traceStore.findById("trace-1"))
                .thenReturn(Optional.of(trace("trace-1", null)));

        assertThatThrownBy(() -> service.update("trace-1", "negative", owner))
                .isInstanceOf(TraceFeedbackService.TraceOwnershipException.class)
                .hasMessageContaining("owner-scoped Session");
        assertThatThrownBy(() -> service.update("trace-1", "maybe", owner))
                .isInstanceOf(TraceFeedbackService.InvalidFeedbackException.class);
    }

    @Test
    void storeUpdateFailure_isNotReportedAsSuccess() {
        AgentTrace trace = trace("trace-1", "session-1");
        var owner = new SessionRequestOwnerResolver.Owner("user-1", null);
        when(traceStore.findById("trace-1")).thenReturn(Optional.of(trace));
        when(sessionStore.findByIdAndOwner("session-1", "user-1", null))
                .thenReturn(Optional.of(mock(Session.class)));
        when(traceStore.updateMetadata(
                "trace-1", java.util.Map.of("user_feedback", "negative")))
                .thenReturn(false);

        assertThatThrownBy(() -> service.update("trace-1", "negative", owner))
                .isInstanceOf(TraceFeedbackService.FeedbackWriteException.class)
                .hasMessageContaining("disappeared");
    }

    private AgentTrace trace(String traceId, String sessionId) {
        return AgentTrace.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .metadata(java.util.Map.of())
                .build();
    }
}
