package com.harness.agent.memory;

import com.harness.agent.SessionInbox;
import com.harness.agent.SessionResumeDispatcher;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.core.model.Session;
import com.harness.core.runtime.RunTrace;
import com.harness.input.memory.InMemorySessionMessageCache;
import com.harness.input.memory.MessageStore;
import com.harness.input.memory.SessionLifecycleManager;
import com.harness.input.memory.SessionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentMemoryRuntimeSessionTest {
    @Test
    void resolvedSessionWaitsBeforeLoadingTheLatestHistory() throws Exception {
        var runtime = mock(AgentMemoryRuntime.class, CALLS_REAL_METHODS);
        var lifecycle = mock(SessionLifecycleManager.class);
        var store = mock(MessageStore.class);
        var session = new Session("created-session", "user", "tenant", "", Instant.now(), Instant.now(), null, Session.SessionStatus.active);
        when(lifecycle.process("user", "tenant", null)).thenReturn(
                new SessionLifecycleManager.LifecycleResult(session, true, List.of()));
        var persisted = new AtomicReference<List<MemoryMessage>>(List.of());
        when(store.loadForContext("created-session")).thenAnswer(call -> persisted.get());
        inject(runtime, "enabled", true);
        inject(runtime, "sessionLifecycle", lifecycle);
        inject(runtime, "sessionStore", mock(SessionStore.class));
        inject(runtime, "sessionContextLoader", new SessionContextLoader(new InMemorySessionMessageCache(), store));
        var dispatcher = new SessionResumeDispatcher(new SessionInbox(), (id, events) -> { });
        var active = dispatcher.acquireForeground("created-session", new CancellationToken());
        var token = new CancellationToken();
        var waiting = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(() -> {
                var lease = new AtomicReference<SessionResumeDispatcher.SessionRunLease>();
                try {
                    return runtime.resolve("user", "tenant", null, "hello", RunTrace.noop(), id -> {
                        waiting.countDown();
                        lease.set(dispatcher.acquireForeground(id, token));
                    });
                } finally { if (lease.get() != null) lease.get().close(); }
            });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            verify(store, never()).loadForContext(anyString());
            var latest = new MemoryMessage(1, "created-session", "previous-run", "assistant",
                    List.of(new MessageBlock(MessageBlock.BlockType.TEXT, "latest reply", null)), false, Instant.now());
            persisted.set(List.of(latest));
            active.close();
            assertThat(future.get(5, TimeUnit.SECONDS).shorttermMessages()).containsExactly(latest);
            verify(store).loadForContext("created-session");
        } finally { token.cancel(); active.close(); worker.shutdownNow(); dispatcher.shutdown(); }
    }

    @Test
    void cancellationAtResolvedSessionPreventsHistoryLoading() throws Exception {
        var runtime = mock(AgentMemoryRuntime.class, CALLS_REAL_METHODS);
        var lifecycle = mock(SessionLifecycleManager.class);
        var loader = mock(SessionContextLoader.class);
        var session = new Session("new-session", "user", "tenant", "", Instant.now(), Instant.now(), null, Session.SessionStatus.active);
        when(lifecycle.process("user", "tenant", null)).thenReturn(
                new SessionLifecycleManager.LifecycleResult(session, true, List.of()));
        inject(runtime, "enabled", true);
        inject(runtime, "sessionLifecycle", lifecycle);
        inject(runtime, "sessionContextLoader", loader);
        assertThatThrownBy(() -> runtime.resolve("user", "tenant", null, "hello", RunTrace.noop(), id -> {
            assertThat(id).isEqualTo("new-session");
            throw new CancellationException("cancelled");
        })).isInstanceOf(CancellationException.class);
        verifyNoInteractions(loader);
    }

    @Test
    void disabledMemoryStillAcquiresTheResolvedSessionGate() {
        var runtime = mock(AgentMemoryRuntime.class, CALLS_REAL_METHODS);
        var resolved = new AtomicReference<String>();
        var context = runtime.resolve("user", "tenant", null, "hello", RunTrace.noop(), resolved::set);
        assertThat(resolved).hasValue(context.sessionId());
        assertThat(context.sessionId()).isNotBlank();
    }

    private static void inject(AgentMemoryRuntime runtime, String name, Object value) throws Exception {
        var field = AgentMemoryRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }
}
