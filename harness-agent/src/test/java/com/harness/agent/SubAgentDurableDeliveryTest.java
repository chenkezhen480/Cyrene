package com.harness.agent;

import com.harness.agent.subagent.InMemorySubAgentTaskRepository;
import com.harness.core.model.CancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class SubAgentDurableDeliveryTest {
    private final AgentRunContext.Owner owner = new AgentRunContext.Owner("user", "tenant", "business");
    private final InMemorySubAgentTaskRepository repository = new InMemorySubAgentTaskRepository(Duration.ofDays(1));

    @Test void parentOpenDefersResumeAndFinishedPendingIdsFollowDurableAcknowledgement() {
        var dispatcher = org.mockito.Mockito.mock(SessionResumeDispatcher.class);
        var inbox = new SessionInbox(repository, Duration.ofMinutes(1));
        var manager = new SubAgentManager(org.mockito.Mockito.mock(com.harness.react.ReActLoopFactory.class),
                com.harness.core.runtime.RunTrace::noop, null,
                org.mockito.Mockito.mock(com.harness.core.model.ArtifactStore.class), inbox, dispatcher,
                org.mockito.Mockito.mock(com.harness.provider.ChatModelProvider.class), repository);
        try {
            var scope = manager.openScope("run");
            var task = SubAgentTask.create("task", "task", null, null, null, List.of(), List.of(), null);
            var record = scope.registerTask(task, new CancellationToken(), "session", "turn", owner, "call", "root", repository);
            manager.detachTask(record);
            record.succeed(new SubAgentResult("task", "done", null, true, SubAgentStatus.SUCCEEDED,
                    List.of(), ToolExecutionSummary.empty(), ContractValidation.notDeclared(), null, 0, "trace"));
            manager.recoverPendingDeliveries();
            org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.never()).requestResume("session");
            assertThat(manager.pendingTaskIds("run")).containsExactly("task");
            var claimed = repository.claimDeliveries("session", Duration.ofMinutes(1), 1).getFirst();
            repository.acknowledgeDelivery(claimed.eventId(), claimed.leaseToken());
            assertThat(manager.pendingTaskIds("run")).isEmpty();
            // A separately pending task proves finishRun explicitly dispatches after the parent tail.
            var next = SubAgentTask.create("next", "task", null, null, null, List.of(), List.of(), null);
            var pending = scope.registerTask(next, new CancellationToken(), "session", "turn", owner, "next-call", "root", repository);
            manager.detachTask(pending);
            pending.fail(SubAgentResult.failure("next", "failed", 0, false));
            manager.finishRun("run");
            org.mockito.Mockito.verify(dispatcher).requestResume("session");
        } finally { manager.shutdown(); }
    }

    @Test void failedCallbackLeavesEventPendingWithoutImmediateRetryLoop() {
        var record = completedTask();
        var inbox = new SessionInbox(repository, Duration.ofMinutes(1));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var dispatcher = new SessionResumeDispatcher(inbox, (session, events) -> {
            attempts.incrementAndGet(); throw new IllegalStateException("session unavailable");
        });
        dispatcher.requestResume("session");
        dispatcher.shutdown();
        assertThat(attempts).hasValue(1);
        assertThat(repository.findAuthorized(owner, "session", record.taskId()).orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.DETACHED);
    }

    @Test void stopCancelsActiveResumeAndSuppressesItsLeasedEvent() throws Exception {
        var record = completedTask();
        var inbox = new SessionInbox(repository, Duration.ofMinutes(1));
        var token = new CancellationToken();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcherRef = new java.util.concurrent.atomic.AtomicReference<SessionResumeDispatcher>();
        var dispatcher = new SessionResumeDispatcher(inbox, (session, events) -> {
            var current = dispatcherRef.get(); current.registerResumeToken(session, token); started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            finally { current.unregisterResumeToken(session, token); }
        });
        dispatcherRef.set(dispatcher);
        try {
            dispatcher.requestResume("session");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.findAuthorized(owner, "session", record.taskId()).orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.DELIVERY_CLAIMED);
            assertThat(dispatcher.cancelSession("session")).isTrue();
            assertThat(token.isCancelled()).isTrue();
        } finally { release.countDown(); dispatcher.shutdown(); }
        assertThat(repository.findAuthorized(owner, "session", record.taskId()).orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.SUPPRESSED);
    }

    private SubAgentTaskRecord completedTask() {
        String id = SubAgentManager.generateTaskId();
        var task = SubAgentTask.create(id, "task", null, null, null, List.of(), List.of(), null);
        var record = new SubAgentTaskRecord(id, "run", "session", "turn", task, new CancellationToken(), owner, "call", "root", repository);
        repository.create(record); record.detach();
        record.succeed(new SubAgentResult(id, "done", null, true, SubAgentStatus.SUCCEEDED,
                List.of(), ToolExecutionSummary.empty(), ContractValidation.notDeclared(), null, 0, "trace"));
        return record;
    }
}
