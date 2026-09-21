package com.harness.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.KnowledgeGraphTool;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.core.runtime.RunTrace;
import com.harness.provider.RealtimeEvent;
import com.harness.provider.RealtimeEventListener;
import com.harness.provider.RealtimeSession;
import com.harness.provider.RealtimeSessionState;
import com.harness.provider.RealtimeToolCall;
import com.harness.provider.RealtimeToolResult;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.Tool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.confirmation.ConfirmationExecutionContext;
import com.harness.tool.skill.LoadSkillTool;
import com.harness.tool.web.AuthorizedUrlContext;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** Executes realtime function calls through the existing immutable catalog and ToolExecutor. */
public final class RealtimeToolBridge implements RealtimeEventListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String tenantId;
    private final String userId;
    private final String sessionId;
    private final RunToolCatalog catalog;
    private final ToolExecutor executor;
    private final ConfirmationExecutionContext confirmationContext;
    private final RunTrace trace;
    private final RealtimeEventListener downstream;
    private final AtomicInteger stepNumber = new AtomicInteger();
    private final AtomicBoolean traceFinished = new AtomicBoolean();
    private final java.util.Set<Thread> activeTools = ConcurrentHashMap.newKeySet();
    private volatile RealtimeSession session;

    public RealtimeToolBridge(
            String tenantId,
            String userId,
            String sessionId,
            RunToolCatalog catalog,
            ToolExecutor executor,
            ConfirmationExecutionContext confirmationContext,
            RunTrace trace,
            RealtimeEventListener downstream
    ) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.confirmationContext = confirmationContext;
        this.trace = Objects.requireNonNull(trace, "trace");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    public void attach(RealtimeSession session) {
        if (this.session != null) {
            throw new IllegalStateException("realtime bridge is already attached");
        }
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public void onEvent(RealtimeEvent event) {
        downstream.onEvent(event);
        if (event.usage() != null) {
            trace.recordModelUsage(event.usage());
        }
        if (event.type() == RealtimeEvent.Type.TOOL_CALL && event.toolCall() != null) {
            Thread.startVirtualThread(() -> {
                Thread current = Thread.currentThread();
                activeTools.add(current);
                try {
                    execute(event.toolCall());
                } finally {
                    activeTools.remove(current);
                }
            });
        } else if (event.type() == RealtimeEvent.Type.CLOSED) {
            activeTools.forEach(Thread::interrupt);
            finishTrace();
        }
    }

    private void execute(RealtimeToolCall realtimeCall) {
        ToolCall call;
        try {
            JsonNode arguments = MAPPER.readTree(realtimeCall.arguments());
            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException("tool arguments must be a JSON object");
            }
            call = new ToolCall(realtimeCall.callId(), realtimeCall.name(), arguments);
        } catch (Exception e) {
            sendFailure(realtimeCall, "Invalid tool arguments: " + e.getMessage());
            return;
        }

        Tool tool = catalog.get(call.toolName());
        if (tool == null) {
            sendFailure(realtimeCall, "Tool is not authorized in this realtime session");
            return;
        }

        ToolResult result;
        long started = System.nanoTime();
        activateToolContext();
        try {
            result = executor.executeAuthorized(call, tool, confirmationContext);
        } catch (RuntimeException exception) {
            result = ToolResult.fail(call.id(), call.toolName(),
                    "Tool execution failed: " + exception.getMessage(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } finally {
            clearToolContext();
        }
        trace.addStep(new ReActStep(
                stepNumber.incrementAndGet(), null, call.toolName(), List.of(call), List.of(result),
                result.success() ? result.output() : result.error(), null));

        String output = result.success()
                ? result.content().modelContent()
                : result.error();
        sendResult(realtimeCall, output, !result.success());
    }

    private void sendFailure(RealtimeToolCall call, String error) {
        sendResult(call, error, true);
    }

    private void sendResult(RealtimeToolCall call, String output, boolean error) {
        RealtimeSession active = session;
        if (active == null || active.state() == RealtimeSessionState.CLOSED
                || active.state() == RealtimeSessionState.CLOSING
                || active.state() == RealtimeSessionState.FAILED) {
            return;
        }
        active.sendToolResult(new RealtimeToolResult(call.callId(), output, error));
        downstream.onEvent(new RealtimeEvent(
                RealtimeEvent.Type.TOOL_RESULT, sessionId, output, null, call, null,
                error ? output : null));
    }

    private void activateToolContext() {
        LoadSkillTool.setCurrentSession(sessionId);
        AuthorizedUrlContext.clear();
        KnowledgeGraphTool.setCurrentContext(tenantId, null);
        KnowledgeAccessService.setCurrentContext(tenantId, null);
        KnowledgeToolRuntimeContext.activate(tenantId, userId, null, null, catalog, trace);
    }

    private static void clearToolContext() {
        LoadSkillTool.clearCurrentSession();
        AuthorizedUrlContext.clear();
        KnowledgeGraphTool.clearCurrentContext();
        KnowledgeAccessService.clearCurrentContext();
        KnowledgeToolRuntimeContext.clear();
    }

    private void finishTrace() {
        if (traceFinished.compareAndSet(false, true)) {
            trace.finish();
        }
    }
}
