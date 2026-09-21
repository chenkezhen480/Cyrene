package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.provider.RealtimeCapabilities;
import com.harness.provider.RealtimeEvent;
import com.harness.provider.RealtimeEventListener;
import com.harness.provider.RealtimeInput;
import com.harness.provider.RealtimeModelProvider;
import com.harness.provider.RealtimeSession;
import com.harness.provider.RealtimeSessionState;
import com.harness.provider.RealtimeSessionUpdate;
import com.harness.provider.RealtimeToolResult;
import io.javalin.http.Context;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSessionHandlerTest {

    private RealtimeSessionHandler handler;

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.close();
        }
        EnvConfig.init(Map.of());
    }

    @Test
    void clientDisconnectClosesProviderOnceAndTokenProtectsConnection() {
        Fixture fixture = fixture(60);
        Map<?, ?> response = fixture.create();
        String sessionId = response.get("sessionId").toString();
        String token = response.get("webSocketToken").toString();

        WsConnectContext rejected = socket(sessionId, "wrong");
        handler.connect(rejected);
        verify(rejected).closeSession(eq(1008), anyString());

        WsConnectContext accepted = socket(sessionId, token);
        handler.connect(accepted);
        fixture.listener.get().onEvent(RealtimeEvent.simple(
                RealtimeEvent.Type.SESSION_READY, sessionId));
        verify(accepted).send(anyString());

        WsCloseContext close = mock(WsCloseContext.class);
        when(close.pathParam("sessionId")).thenReturn(sessionId);
        handler.disconnected(close);
        handler.disconnected(close);

        assertThat(fixture.session.closeCount.get()).isEqualTo(1);
    }

    @Test
    void providerDisconnectRemovesSessionAndTimeoutCancelsAnotherSession() throws Exception {
        Fixture providerClose = fixture(60);
        Map<?, ?> response = providerClose.create();
        String sessionId = response.get("sessionId").toString();
        providerClose.listener.get().onEvent(RealtimeEvent.simple(
                RealtimeEvent.Type.CLOSED, sessionId));

        WsConnectContext afterClose = socket(sessionId, response.get("webSocketToken").toString());
        handler.connect(afterClose);
        verify(afterClose).closeSession(eq(1008), anyString());
        handler.close();

        Fixture timeout = fixture(1);
        timeout.create();
        assertThat(timeout.session.closed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(timeout.session.closeCount.get()).isEqualTo(1);
    }

    private Fixture fixture(long timeoutSeconds) {
        EnvConfig.init(Map.of(
                EnvKey.AUTH_MODE, "none",
                EnvKey.REALTIME_SESSION_TIMEOUT_SECONDS, Long.toString(timeoutSeconds)));
        AgentOrchestrator agent = mock(AgentOrchestrator.class);
        RealtimeModelProvider provider = mock(RealtimeModelProvider.class);
        when(provider.providerName()).thenReturn("test");
        when(provider.capabilities()).thenReturn(new RealtimeCapabilities(
                true, true, true, true, true, true, true));
        when(agent.realtimeModel()).thenReturn(provider);
        FakeSession session = new FakeSession();
        AtomicReference<RealtimeEventListener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(6));
            return session;
        }).when(agent).openRealtimeSession(
                anyString(), isNull(), eq("user-1"), anyString(), any(), any(), any());
        handler = new RealtimeSessionHandler(agent, new ObjectMapper());
        return new Fixture(agent, session, listener);
    }

    private WsConnectContext socket(String sessionId, String token) {
        WsConnectContext context = mock(WsConnectContext.class);
        when(context.pathParam("sessionId")).thenReturn(sessionId);
        when(context.queryParam("token")).thenReturn(token);
        return context;
    }

    private final class Fixture {
        private final AgentOrchestrator agent;
        private final FakeSession session;
        private final AtomicReference<RealtimeEventListener> listener;

        private Fixture(
                AgentOrchestrator agent,
                FakeSession session,
                AtomicReference<RealtimeEventListener> listener
        ) {
            this.agent = agent;
            this.session = session;
            this.listener = listener;
        }

        private Map<?, ?> create() {
            Context context = mock(Context.class);
            when(context.bodyAsClass(RealtimeSessionHandler.CreateRequest.class)).thenReturn(
                    new RealtimeSessionHandler.CreateRequest(
                            "user-1", null, null, null, null,
                            true, 16_000, 24_000, "server_vad"));
            when(context.status(201)).thenReturn(context);
            AtomicReference<Map<?, ?>> response = new AtomicReference<>();
            doAnswer(invocation -> {
                response.set(invocation.getArgument(0));
                return context;
            }).when(context).json(any(Object.class));

            handler.create(context);

            assertThat(listener.get()).isNotNull();
            assertThat(response.get()).isNotNull();
            verify(agent).openRealtimeSession(anyString(), isNull(), eq("user-1"),
                    anyString(), any(), any(), any());
            return response.get();
        }
    }

    private static final class FakeSession implements RealtimeSession {
        private final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile RealtimeSessionState state = RealtimeSessionState.READY;

        @Override
        public String sessionId() {
            return "provider-session";
        }

        @Override
        public void send(RealtimeInput input) {
        }

        @Override
        public void update(RealtimeSessionUpdate update) {
        }

        @Override
        public void sendToolResult(RealtimeToolResult result) {
        }

        @Override
        public void interrupt() {
        }

        @Override
        public RealtimeSessionState state() {
            return state;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            state = RealtimeSessionState.CLOSED;
            closed.countDown();
        }
    }
}
