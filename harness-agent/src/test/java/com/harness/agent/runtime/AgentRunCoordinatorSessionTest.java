package com.harness.agent.runtime;

import com.harness.agent.*;
import com.harness.agent.lifecycle.AgentLifecycleHooks;
import com.harness.agent.memory.AgentMemoryRuntime;
import com.harness.core.model.AgentContext;
import com.harness.core.model.CancellationToken;
import com.harness.core.runtime.RunTrace;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.trace.ReplyAuditor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRunCoordinatorSessionTest {
    @Test void blockingRequestCanBeCancelledBeforePreparingAnOccupiedSession() throws Exception { occupiedSession(false); }
    @Test void streamingRequestCanBeCancelledBeforePreparingAnOccupiedSession() throws Exception { occupiedSession(true); }

    @Test void blockingFreshSessionHoldsItsGateDuringPreparationAndReleasesOnFailure() throws Exception { freshSession(false); }
    @Test void streamingFreshSessionHoldsItsGateDuringPreparationAndReleasesOnFailure() throws Exception { freshSession(true); }

    private void freshSession(boolean streaming) throws Exception {
        var dispatcher = new SessionResumeDispatcher(new SessionInbox(), (session, events) -> { });
        var runtime = mock(AgentRuntime.class);
        when(runtime.startTrace()).thenReturn(RunTrace.noop());
        var preparer = mock(AgentRunPreparer.class);
        var preparing = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        when(preparer.prepare(any(), any(), any())).thenAnswer(call -> {
            Consumer<String> beforeHistoryLoad = call.getArgument(2);
            beforeHistoryLoad.accept("created-session");
            preparing.countDown();
            assertThat(releasePreparation.await(5, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("Preparation failed");
        });
        var coordinator = new AgentRunCoordinator(runtime, preparer, mock(AgentMemoryRuntime.class),
                mock(ToolRegistry.class), mock(ToolExecutor.class), mock(SubAgentManager.class), dispatcher,
                mock(ReplyAuditor.class), mock(AgentLifecycleHooks.class));
        var token = new CancellationToken();
        var command = new AgentRunCoordinator.AgentRunCommand(null, "hello", List.of(), null, null,
                token, null, "user", AgentContext.empty());
        var worker = Executors.newFixedThreadPool(2);
        var eventType = new AtomicReference<String>();
        try {
            var foreground = worker.submit(() -> {
                if (streaming) coordinator.stream(command, event -> eventType.set(event.type().name()));
                else coordinator.run(command);
            });
            assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
            var contenderStarted = new CountDownLatch(1);
            var contender = worker.submit(() -> {
                contenderStarted.countDown();
                try (var lease = dispatcher.acquireForeground("created-session", token)) {
                    return lease.sessionId();
                }
            });
            assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> contender.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releasePreparation.countDown();
            if (streaming) {
                foreground.get(5, TimeUnit.SECONDS);
                assertThat(eventType).hasValue("ERROR");
            } else assertThatThrownBy(() -> foreground.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo("created-session");
        } finally {
            releasePreparation.countDown(); token.cancel(); worker.shutdownNow(); dispatcher.shutdown();
        }
    }

    private void occupiedSession(boolean streaming) throws Exception {
        var dispatcher = new SessionResumeDispatcher(new SessionInbox(), (session, events) -> { });
        var runtime = mock(AgentRuntime.class);
        var preparer = mock(AgentRunPreparer.class);
        var entered = new CountDownLatch(1);
        when(runtime.startTrace()).thenAnswer(call -> { entered.countDown(); return RunTrace.noop(); });
        when(preparer.prepare(any(), any(), any())).thenThrow(new IllegalStateException("Preparation must wait for the current run"));
        var coordinator = new AgentRunCoordinator(runtime, preparer, mock(AgentMemoryRuntime.class),
                mock(ToolRegistry.class), mock(ToolExecutor.class), mock(SubAgentManager.class), dispatcher,
                mock(ReplyAuditor.class), mock(AgentLifecycleHooks.class));
        var token = new CancellationToken();
        var command = new AgentRunCoordinator.AgentRunCommand(null, "hello", List.of(), "session", null,
                token, null, "user", AgentContext.empty());
        var eventType = new AtomicReference<String>();
        var worker = Executors.newSingleThreadExecutor();
        var active = dispatcher.acquireForeground("session", new CancellationToken());
        try {
            var future = worker.submit(() -> {
                if (streaming) coordinator.stream(command, event -> eventType.set(event.type().name()));
                else coordinator.run(command);
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            token.cancel();
            if (streaming) {
                future.get(5, TimeUnit.SECONDS);
                assertThat(eventType).hasValue("CANCELLED");
            } else assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
            verify(preparer, never()).prepare(any(), any(), any());
        } finally { token.cancel(); active.close(); worker.shutdownNow(); dispatcher.shutdown(); }
    }
}
