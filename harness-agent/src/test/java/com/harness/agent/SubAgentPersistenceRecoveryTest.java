package com.harness.agent;

import com.harness.agent.subagent.InMemorySubAgentTaskRepository;
import com.harness.core.model.ArtifactStore;
import com.harness.core.model.CancellationToken;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.ChatModelProvider;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopFactory;
import com.harness.react.ReActResult;
import com.harness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubAgentPersistenceRecoveryTest {
    private final AgentRunContext.Owner owner = new AgentRunContext.Owner("user", "tenant", "business");
    private final InMemorySubAgentTaskRepository repository = spy(new InMemorySubAgentTaskRepository(Duration.ofDays(1)));
    private final AtomicBoolean offline = new AtomicBoolean();
    private final SessionResumeDispatcher dispatcher = mock(SessionResumeDispatcher.class);
    private final ReActLoop loop = mock(ReActLoop.class);

    @Test void cancelledParentSuppressesCompletedResultsAcrossRestartWithoutCancellingANewRun() {
        SubAgentManager manager = manager();
        CancellationToken parent = new CancellationToken();
        try {
            var scope = manager.openScope("cancelled-run", "session", event -> { });
            var record = scope.registerTask(definition("cancelled"), CancellationToken.createChild(parent),
                    "session", "turn", owner, "call", "root", repository);
            record.succeed(SubAgentResult.success("cancelled", "done",
                    new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                            ContractValidation.notDeclared(), null), 0, null));
            var current = task(manager.openScope("new-run", "session", event -> { }), "current");
            parent.cancel();
            manager.finishRun("cancelled-run");
            assertThat(repository.findAuthorized(owner, "session", "cancelled").orElseThrow().deliveryState())
                    .isEqualTo(ResultDeliveryState.SUPPRESSED);
            assertThat(current.taskCancellationToken().isCancelled()).isFalse();
            assertThat(repository.findAuthorized(owner, "session", "current").orElseThrow().deliveryState())
                    .isEqualTo(ResultDeliveryState.INLINE_PENDING);
            current.succeed(SubAgentResult.success("current", "done",
                    new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                            ContractValidation.notDeclared(), null), 0, null));
            current.consumeInline();
            manager.finishRun("new-run");
        } finally { manager.shutdown(); }
        clearInvocations(dispatcher);
        manager = manager();
        try {
            manager.recoverPendingDeliveries();
            assertThat(repository.findAuthorized(owner, "session", "cancelled").orElseThrow().deliveryState())
                    .isEqualTo(ResultDeliveryState.SUPPRESSED);
            verify(dispatcher, never()).requestResume("session");
        } finally { manager.shutdown(); }
    }

    @Test void cancelledScopeIsRetainedUntilTaskSuppressionIsDurable() {
        SubAgentManager manager = manager();
        CancellationToken parent = new CancellationToken();
        try {
            var scope = manager.openScope("cancelled-run", "session", event -> { });
            var record = scope.registerTask(definition("cancelled"), CancellationToken.createChild(parent),
                    "session", "turn", owner, "call", "root", repository);
            record.succeed(SubAgentResult.success("cancelled", "done",
                    new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                            ContractValidation.notDeclared(), null), 0, null));
            // A committed result still needs a durable cancellation decision before scope cleanup.
            doAnswer(call -> { if (offline.get()) throw new IllegalStateException("database offline"); return call.callRealMethod(); })
                    .when(repository).suppressTask("cancelled");
            offline.set(true);
            parent.cancel();
            manager.finishRun("cancelled-run");
            assertThat(manager.getScope("cancelled-run")).isNotNull();
            offline.set(false);
            manager.recoverPendingDeliveries();
            assertThat(manager.getScope("cancelled-run")).isNull();
            assertThat(repository.findAuthorized(owner, "session", "cancelled").orElseThrow().deliveryState())
                    .isEqualTo(ResultDeliveryState.SUPPRESSED);
            verify(dispatcher, never()).requestResume("session");
        } finally { offline.set(false); manager.shutdown(); }
    }

    @Test void stopCancelsEveryLiveChildBeforeSuppressionPersistenceFailure() {
        doAnswer(call -> {
            if (offline.get()) throw new IllegalStateException("database offline");
            return call.callRealMethod();
        }).when(repository).suppressSession(anyString());
        doAnswer(call -> {
            if (offline.get()) throw new IllegalStateException("database offline");
            return call.callRealMethod();
        }).when(repository).transition(anyString(), any(), any());
        SubAgentManager manager = manager();
        try {
            SubAgentRunScope scope = manager.openScope("run");
            SubAgentTaskRecord first = task(scope, "first");
            SubAgentTaskRecord second = task(scope, "second");
            first.start(); second.start();
            manager.finishRun("run");
            offline.set(true);
            assertThatThrownBy(() -> manager.cancelSession("session")).hasMessage("database offline");
            assertThat(first.taskCancellationToken().isCancelled()).isTrue();
            assertThat(second.taskCancellationToken().isCancelled()).isTrue();
        } finally { offline.set(false); manager.shutdown(); }
    }

    @Test void individualCancellationInterruptsBeforeItsStateWriteFails() {
        SubAgentRunScope scope = new SubAgentRunScope("run", 2);
        SubAgentTaskRecord record = task(scope, "task");
        record.start();
        doThrow(new IllegalStateException("database offline")).when(repository)
                .transition("task", SubAgentStatus.RUNNING, SubAgentStatus.CANCEL_REQUESTED);
        assertThatThrownBy(record::requestCancel).hasMessage("database offline");
        assertThat(record.taskCancellationToken().isCancelled()).isTrue();
    }

    @Test void completedExecutionRetriesItsOriginalResultWithoutReplayingWork() throws Exception {
        doAnswer(call -> {
            if (offline.get()) throw new IllegalStateException("database offline");
            return call.callRealMethod();
        }).when(repository).complete(anyString(), any(), any());
        offline.set(true);
        when(loop.execute(any())).thenReturn(new ReActResult("completed output", List.of()));
        SubAgentManager manager = manager();
        try {
            manager.openScope("run");
            SubAgentTaskRecord record = manager.submitTask(context(), definition("task"), "session", "call");
            awaitWorkerExit(manager);
            assertThat(record.completion()).isNotDone();
            assertThat(record.persistenceFailure()).hasMessage("database offline");
            assertThat(record.pendingTerminalResult().status()).isEqualTo(SubAgentStatus.SUCCEEDED);
            manager.finishRun("run");
            offline.set(false);
            manager.recoverPendingDeliveries();
            assertThat(record.completion().get(1, TimeUnit.SECONDS).output()).isEqualTo("completed output");
            assertThat(repository.findAuthorized(owner, "session", "task").orElseThrow().status()).isEqualTo(SubAgentStatus.SUCCEEDED);
            assertThat(record.persistenceFailure()).isNull();
            verify(loop, times(1)).execute(any());
        } finally { offline.set(false); manager.shutdown(); }
    }

    @Test void recoveryReconcilesACommittedResultWhenCommitAcknowledgementWasLost() throws Exception {
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        doAnswer(call -> {
            Object changed = call.callRealMethod();
            if (firstWrite.getAndSet(false)) throw new IllegalStateException("commit acknowledgement lost");
            return changed;
        }).when(repository).complete(anyString(), any(), any());
        when(loop.execute(any())).thenReturn(new ReActResult("committed output", List.of()));
        SubAgentManager manager = manager();
        try {
            manager.openScope("run");
            SubAgentTaskRecord record = manager.submitTask(context(), definition("task"), "session", "call");
            awaitWorkerExit(manager);
            manager.finishRun("run");
            manager.recoverPendingDeliveries();
            assertThat(record.completion().get(1, TimeUnit.SECONDS).output()).isEqualTo("committed output");
            assertThat(record.status().get()).isEqualTo(SubAgentStatus.SUCCEEDED);
            verify(loop, times(1)).execute(any());
        } finally { manager.shutdown(); }
    }

    @Test void recoveryDefersAnOldDeliveryWhileTheSameSessionHasAnEmptyOpenScope() {
        SubAgentManager manager = manager();
        try {
            SubAgentRunScope previous = manager.openScope("previous", "session", event -> { });
            SubAgentTaskRecord record = task(previous, "task");
            record.detach();
            record.succeed(SubAgentResult.success("task", "done",
                    new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                            ContractValidation.notDeclared(), null), 0, null));
            manager.finishRun("previous");
            clearInvocations(dispatcher);
            manager.openScope("foreground", "session", event -> { });
            manager.recoverPendingDeliveries();
            verify(dispatcher, never()).requestResume("session");
            manager.finishRun("foreground");
            manager.recoverPendingDeliveries();
            verify(dispatcher).requestResume("session");
        } finally { manager.shutdown(); }
    }

    @Test void failedResumeSuppressionIsRetriedWithoutRepeatingSuccessfulWrites() {
        SessionInbox inbox = mock(SessionInbox.class);
        when(inbox.leaseDuration()).thenReturn(Duration.ofMinutes(1));
        when(inbox.hasPending("session")).thenThrow(new IllegalStateException("database offline"));
        SessionResumeDispatcher realDispatcher = new SessionResumeDispatcher(inbox, (session, events) -> { });
        CancellationToken token = new CancellationToken();
        realDispatcher.registerResumeToken("session", token);
        doThrow(new IllegalStateException("database offline")).when(inbox).suppressSession("session");
        try {
            assertThatThrownBy(() -> realDispatcher.cancelSession("session")).hasMessage("database offline");
            assertThat(token.isCancelled()).isTrue();
            doNothing().when(inbox).suppressSession("session");
            realDispatcher.retryPendingSuppressions();
            clearInvocations(inbox);
            realDispatcher.retryPendingSuppressions();
            verify(inbox, never()).suppressSession("session");
        } finally { realDispatcher.shutdown(); }
    }

    @Test void expiredScopeKeepsCompletedCandidatesAndContinuesAfterOnePersistenceFailure() throws Exception {
        doAnswer(call -> {
            if (offline.get() && "completed".equals(call.getArgument(0))) throw new IllegalStateException("database offline");
            return call.callRealMethod();
        }).when(repository).complete(anyString(), any(), any());
        SubAgentManager manager = manager();
        try {
            SubAgentRunScope scope = manager.openScope("run", "session", event -> { });
            SubAgentTaskRecord completed = task(scope, "completed");
            SubAgentTaskRecord active = task(scope, "active");
            active.start();
            offline.set(true);
            assertThatThrownBy(() -> completed.succeed(SubAgentResult.success("completed", "original result",
                    new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                            ContractValidation.notDeclared(), null), 0, null))).hasMessage("database offline");
            manager.finishRun("run");
            var accessTime = SubAgentRunScope.class.getDeclaredField("lastAccessedAt");
            accessTime.setAccessible(true);
            accessTime.set(scope, java.time.Instant.now().minus(Duration.ofDays(1)));
            var cleanup = SubAgentManager.class.getDeclaredMethod("cleanupExpiredScopes");
            cleanup.setAccessible(true);
            cleanup.invoke(manager);
            assertThat(active.taskCancellationToken().isCancelled()).isTrue();
            assertThat(active.completion().join().status()).isEqualTo(SubAgentStatus.TIMED_OUT);
            assertThat(completed.completion()).isNotDone();
            offline.set(false);
            manager.recoverPendingDeliveries();
            assertThat(completed.completion().get(1, TimeUnit.SECONDS).output()).isEqualTo("original result");
            assertThat(completed.status().get()).isEqualTo(SubAgentStatus.SUCCEEDED);
        } finally { offline.set(false); manager.shutdown(); }
    }

    @Test void aNewTaskFlushesFailedSuppressionBeforeItIsPersisted() {
        SessionInbox inbox = new SessionInbox(repository, Duration.ofMinutes(1));
        SessionResumeDispatcher realDispatcher = new SessionResumeDispatcher(inbox, (session, events) -> { });
        doAnswer(call -> {
            if (offline.get()) throw new IllegalStateException("database offline");
            return call.callRealMethod();
        }).when(repository).suppressSession(anyString());
        ReActLoopFactory factory = mock(ReActLoopFactory.class);
        when(factory.create(any(), any())).thenReturn(loop);
        when(loop.execute(any())).thenReturn(new ReActResult("new result", List.of()));
        SubAgentManager manager = new SubAgentManager(factory, RunTrace::noop, null, mock(ArtifactStore.class),
                inbox, realDispatcher, mock(ChatModelProvider.class), repository);
        try {
            var oldScope = manager.openScope("old", "session", event -> { });
            var old = task(oldScope, "old");
            old.start(); manager.finishRun("old");
            offline.set(true);
            assertThatThrownBy(() -> manager.cancelSession("session")).hasMessage("database offline");
            offline.set(false);
            manager.openScope("run", "session", event -> { });
            var current = manager.submitTask(context(), definition("new"), "session", "new-call");
            manager.recoverPendingDeliveries();
            assertThat(repository.findAuthorized(owner, "session", "old").orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.SUPPRESSED);
            assertThat(repository.findAuthorized(owner, "session", "new").orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.INLINE_PENDING);
            assertThat(current.taskCancellationToken().isCancelled()).isFalse();
        } finally { offline.set(false); manager.shutdown(); realDispatcher.shutdown(); }
    }

    @Test void concurrentSuppressionRecoveryFinishesBeforeANewTaskIsRegistered() throws Exception {
        var retryEntered = new java.util.concurrent.CountDownLatch(1);
        var retryRelease = new java.util.concurrent.CountDownLatch(1);
        var allowEntered = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean pauseNextSuppression = new AtomicBoolean();
        SessionInbox inbox = mock(SessionInbox.class);
        when(inbox.leaseDuration()).thenReturn(Duration.ofMinutes(1));
        doAnswer(call -> {
            if (offline.get()) throw new IllegalStateException("database offline");
            if (pauseNextSuppression.getAndSet(false)) {
                retryEntered.countDown();
                if (!retryRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out");
            }
            repository.suppressSession(call.getArgument(0));
            return null;
        }).when(inbox).suppressSession(anyString());
        SessionResumeDispatcher realDispatcher = new SessionResumeDispatcher(inbox, (session, events) -> { });
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            offline.set(true);
            assertThatThrownBy(() -> realDispatcher.cancelSession("session")).hasMessage("database offline");
            offline.set(false);
            pauseNextSuppression.set(true);
            var retry = workers.submit(realDispatcher::retryPendingSuppressions);
            assertThat(retryEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var allowed = workers.submit(() -> {
                allowEntered.countDown();
                realDispatcher.allowSession("session");
                var scope = new SubAgentRunScope("new-run", "session", 1, event -> { });
                return task(scope, "new");
            });
            assertThat(allowEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> allowed.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            retryRelease.countDown();
            retry.get(2, TimeUnit.SECONDS);
            allowed.get(2, TimeUnit.SECONDS);
            assertThat(repository.findAuthorized(owner, "session", "new").orElseThrow().deliveryState()).isEqualTo(ResultDeliveryState.INLINE_PENDING);
        } finally {
            retryRelease.countDown(); workers.shutdownNow(); realDispatcher.shutdown();
        }
    }


    @Test void finishedScopeKeepsPersistedResultsUntilDeliveryTransitionSucceeds() throws Exception {
        doAnswer(call -> {
            if (offline.get()) throw new IllegalStateException("database offline");
            return call.callRealMethod();
        }).when(repository).changeDelivery(anyString(), any(), any());
        SubAgentManager manager = manager();
        try {
            var scope = manager.openScope("run", "session", event -> { });
            var first = task(scope, "first");
            var second = task(scope, "second");
            for (var record : List.of(first, second)) {
                record.succeed(SubAgentResult.success(record.taskId(), "persisted result",
                        new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                                ContractValidation.notDeclared(), null), 0, null));
            }
            offline.set(true);
            manager.finishRun("run");
            assertThat(manager.getScope("run")).isSameAs(scope);
            assertThat(first.pendingTerminalResult()).isNull();
            assertThat(first.completion()).isCompleted();
            manager.recoverPendingDeliveries();
            assertThat(manager.getScope("run")).isSameAs(scope);
            var accessTime = SubAgentRunScope.class.getDeclaredField("lastAccessedAt");
            accessTime.setAccessible(true);
            accessTime.set(scope, java.time.Instant.now().minus(Duration.ofDays(1)));
            var cleanup = SubAgentManager.class.getDeclaredMethod("cleanupExpiredScopes");
            cleanup.setAccessible(true);
            cleanup.invoke(manager);
            assertThat(manager.getScope("run")).isSameAs(scope);
            verify(repository, atLeast(3)).changeDelivery("second", ResultDeliveryState.INLINE_PENDING, ResultDeliveryState.DETACHED);
            offline.set(false);
            manager.recoverPendingDeliveries();
            for (var record : List.of(first, second)) {
                assertThat(repository.findAuthorized(owner, "session", record.taskId()).orElseThrow().deliveryState())
                        .isEqualTo(ResultDeliveryState.DETACHED);
                assertThat(record.completion().join().output()).isEqualTo("persisted result");
            }
            assertThat(manager.getScope("run")).isNull();
            verify(dispatcher, atLeastOnce()).requestResume("session");
            verify(loop, never()).execute(any());
        } finally { offline.set(false); manager.shutdown(); }
    }

    @Test void retryingDetachmentCannotResumeOverANewForegroundRun() {
        doAnswer(call -> {
            if (offline.get()) throw new IllegalStateException("database offline");
            return call.callRealMethod();
        }).when(repository).changeDelivery(anyString(), any(), any());
        SubAgentManager manager = manager();
        try {
            var scope = manager.openScope("previous", "session", event -> { });
            var record = task(scope, "task");
            record.succeed(SubAgentResult.success("task", "persisted result",
                    new SubAgentCompletionContractValidator.Evaluation(List.of(), ToolExecutionSummary.empty(),
                            ContractValidation.notDeclared(), null), 0, null));
            offline.set(true);
            manager.finishRun("previous");
            manager.openScope("foreground", "session", event -> { });
            offline.set(false);
            manager.recoverPendingDeliveries();
            verify(dispatcher, never()).requestResume("session");
            manager.finishRun("foreground");
            manager.recoverPendingDeliveries();
            verify(dispatcher).requestResume("session");
        } finally { offline.set(false); manager.shutdown(); }
    }

    private SubAgentManager manager() {
        ReActLoopFactory factory = mock(ReActLoopFactory.class);
        when(factory.create(any(), any())).thenReturn(loop);
        return new SubAgentManager(factory, RunTrace::noop, null, mock(ArtifactStore.class),
                new SessionInbox(repository, Duration.ofMinutes(1)), dispatcher,
                mock(ChatModelProvider.class), repository);
    }

    private SubAgentTaskRecord task(SubAgentRunScope scope, String id) {
        return scope.registerTask(definition(id), new CancellationToken(), "session", "turn", owner,
                "call-" + id, "trace", repository);
    }

    private static SubAgentTask definition(String id) {
        return SubAgentTask.create(id, "task", null, null, null, List.of(), List.of(), null);
    }

    private AgentRunContext context() {
        return new AgentRunContext("run", "session", new CancellationToken(), "trace",
                new ToolRegistry().snapshot(), "turn", owner);
    }

    private void awaitWorkerExit(SubAgentManager manager) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && (mockingDetails(loop).getInvocations().isEmpty()
                || manager.getActiveTaskCount() != 0); attempt++) Thread.sleep(10);
        assertThat(mockingDetails(loop).getInvocations()).isNotEmpty();
        assertThat(manager.getActiveTaskCount()).isZero();
    }
}
