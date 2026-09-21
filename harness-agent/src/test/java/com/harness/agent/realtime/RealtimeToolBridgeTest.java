package com.harness.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolSpec;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.RealtimeEvent;
import com.harness.provider.RealtimeInput;
import com.harness.provider.RealtimeSession;
import com.harness.provider.RealtimeSessionState;
import com.harness.provider.RealtimeSessionUpdate;
import com.harness.provider.RealtimeToolCall;
import com.harness.provider.RealtimeToolResult;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.Tool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.confirmation.ConfirmationManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeToolBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void executesOnlyToolsVisibleInThisSessionsCatalog() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("allowed"));
        registry.register(tool("other-identity-only"));
        RunToolCatalog catalog = registry.snapshot().allowing(java.util.Set.of("allowed"));
        FakeSession session = new FakeSession();
        List<RealtimeEvent> events = new CopyOnWriteArrayList<>();
        RealtimeToolBridge bridge = new RealtimeToolBridge(
                "tenant", "user", "session", catalog,
                new ToolExecutor(new ConfirmationManager(Duration.ofMinutes(1))),
                null, RunTrace.noop(), events::add);
        bridge.attach(session);

        bridge.onEvent(toolCall("call-1", "allowed"));
        awaitResults(session, 1);
        assertThat(session.results.getFirst().error()).isFalse();
        assertThat(session.results.getFirst().output()).isEqualTo("ok");

        bridge.onEvent(toolCall("call-2", "other-identity-only"));
        awaitResults(session, 2);
        assertThat(session.results.get(1).error()).isTrue();
        assertThat(session.results.get(1).output()).contains("not authorized");
        assertThat(events).extracting(RealtimeEvent::type)
                .contains(RealtimeEvent.Type.TOOL_CALL, RealtimeEvent.Type.TOOL_RESULT);
    }

    private static RealtimeEvent toolCall(String id, String name) {
        return new RealtimeEvent(RealtimeEvent.Type.TOOL_CALL, "session", null, null,
                new RealtimeToolCall(id, name, "{}"), null, null);
    }

    private static Tool tool(String name) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "test", schema, ToolCapability.RETRIEVAL);
            }

            @Override
            public String execute(JsonNode arguments) {
                return "ok";
            }
        };
    }

    private static void awaitResults(FakeSession session, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (session.results.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(session.results).hasSize(count);
    }

    private static final class FakeSession implements RealtimeSession {
        private final List<RealtimeToolResult> results = new CopyOnWriteArrayList<>();

        @Override public String sessionId() { return "session"; }
        @Override public void send(RealtimeInput input) { }
        @Override public void update(RealtimeSessionUpdate update) { }
        @Override public void sendToolResult(RealtimeToolResult result) { results.add(result); }
        @Override public void interrupt() { }
        @Override public RealtimeSessionState state() { return RealtimeSessionState.READY; }
        @Override public void close() { }
    }
}
