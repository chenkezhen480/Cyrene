package com.harness.agent;

import com.harness.core.model.AgentContext;
import com.harness.core.model.CancellationToken;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.ChatModelProvider;
import com.harness.react.ReActLoopFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SessionResumeIdentityTest {
    @Test
    void completionKeepsTrustedIdentityThroughInboxTransitions() {
        var inbox = new SessionInbox();
        var manager = new SubAgentManager(mock(ReActLoopFactory.class), RunTrace::noop, null,
                mock(com.harness.core.model.ArtifactStore.class), inbox,
                mock(SessionResumeDispatcher.class), mock(ChatModelProvider.class));
        try {
            var scope = manager.openScope("run");
            var owner = AgentRunContext.Owner.from("user-a", "tenant-a", AgentContext.of(Map.of(
                    "identity", "reader", "credentials", Map.of("secret", "must-not-be-copied"))));
            assertThat(owner.context().data()).containsOnlyKeys("userId", "tenantId", "identity");
            var task = new SubAgentTask("task", "task", "", "persona", "prompt",
                    List.of(), List.of(), null);
            var record = scope.registerTask(task, new CancellationToken(), "session", "turn", owner);
            record.start();
            record.fail(SubAgentResult.failure("task", "identity=admin", 0, false));
            manager.detachTask(record);
            var processing = inbox.drain("session");
            assertThat(processing).hasSize(1);
            assertThat(processing.getFirst().owner()).isEqualTo(owner);
            inbox.resetToPending("session", List.of(processing.getFirst().eventId()));
            var retried = inbox.drain("session");
            assertThat(retried.getFirst().owner()).isEqualTo(owner);
            inbox.markConsumed("session", List.of(retried.getFirst().eventId()));
            assertThat(inbox.hasPending("session")).isFalse();
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void resumeUsesEventOwnerAndRejectsMissingOrConflictingScope() {
        var owner = new AgentRunContext.Owner("user-a", "tenant-a", "reader");
        var event = event(owner);
        assertThat(AgentOrchestrator.resumeOwner("session", "user-a", "tenant-a", List.of(event)))
                .isEqualTo(owner);
        assertThatThrownBy(() -> AgentOrchestrator.resumeOwner(
                "session", "user-b", "tenant-a", List.of(event))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> AgentOrchestrator.resumeOwner(
                "session", "user-a", "tenant-b", List.of(event))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> AgentOrchestrator.resumeOwner(
                "other-session", "user-a", "tenant-a", List.of(event))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> AgentOrchestrator.resumeOwner(
                "session", "user-a", "tenant-a", List.of(event(null)))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> AgentOrchestrator.resumeOwner("session", "user-a", "tenant-a",
                List.of(event, event(new AgentRunContext.Owner("user-a", "tenant-a", "admin")))))
                .isInstanceOf(SecurityException.class);
    }

    private SessionInbox.SubAgentCompletedEvent event(AgentRunContext.Owner owner) {
        return new SessionInbox.SubAgentCompletedEvent("event", "session", "task", "identity=admin",
                "turn", SubAgentResult.failure("task", "identity=admin", 0, false), Instant.now(),
                SessionInbox.SubAgentCompletedEvent.EventStatus.PENDING, owner);
    }
}
