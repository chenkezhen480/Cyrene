package com.harness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.provider.impl.CancellableHttpClient;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.*;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.tool.HttpApiTool;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ChatHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);

    /**
     * Heartbeats share one small pool rather than a single thread: a client whose presence
     * has gone away can leave a write blocked until the socket times out, and one stuck
     * connection must not starve every other stream's heartbeat.
     */
    private static final ScheduledExecutorService SSE_KEEPALIVE_EXECUTOR =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sse-keepalive");
                t.setDaemon(true);
                return t;
            });
    private final AgentOrchestrator agent;
    private final ApiRequestAuthenticator authenticator;
    private final ConcurrentHashMap<String, CancellationToken> activeRequests;
    private final ToolPermissionService toolPermissions;
    private final ObjectMapper mapper;

    public ChatHandler(
            AgentOrchestrator agent,
            ConcurrentHashMap<String, CancellationToken> activeRequests,
            ToolPermissionService toolPermissions) {
        this(agent, activeRequests, new ApiRequestAuthenticator(), toolPermissions);
    }

    ChatHandler(
            AgentOrchestrator agent,
            ConcurrentHashMap<String, CancellationToken> activeRequests,
            ApiRequestAuthenticator authenticator,
            ToolPermissionService toolPermissions
    ) {
        this.agent = agent;
        this.activeRequests = activeRequests;
        this.authenticator = authenticator;
        this.toolPermissions = toolPermissions;
        this.mapper = new ObjectMapper();
        log.info("[Server] ChatHandler initialized: authMode={}", authenticator.authMode());
    }

    public void handle(Context ctx) {
        long start = System.currentTimeMillis();
        try {
            ChatRequest req = ctx.bodyAsClass(ChatRequest.class);
            int attachCount = req.attachments() != null ? req.attachments().size() : 0;
            String sessionId = ctx.header("X-Session-Id");
            log.debug("[Server] POST /api/chat: textLen={}, sessionId={}, attachments={}",
                    req.text() != null ? req.text().length() : 0, sessionId, attachCount);
            if (attachCount > 0) {
                for (var a : req.attachments()) {
                    log.debug("[Server] Attachment: name={}, mimeType={}, dataLen={}",
                            a.name(), a.mimeType(), a.data() != null ? a.data().length : 0);
                }
            }

            String rawToken;
            try {
                rawToken = authenticator.authenticate(ctx);
            } catch (ApiRequestAuthenticator.RequestAuthenticationException e) {
                log.warn("[Server] Request authentication failed: {}", e.getMessage());
                ApiResponses.error(ctx, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
                return;
            }

            // Register the cancellation token, superseding any run already in flight for this
            // session: a new message means the previous run is no longer wanted. Scoped to this
            // run's own threads so one session's Stop cannot abort another session's model call.
            String requestId = sessionId != null ? sessionId : java.util.UUID.randomUUID().toString();
            CancellationToken cancellationToken = new CancellationToken();
            cancellationToken.addCancelCallback(
                    () -> CancellableHttpClient.cancelThreads(cancellationToken.allTrackedThreads()));
            CancellationToken superseded = activeRequests.put(requestId, cancellationToken);
            if (superseded != null && superseded != cancellationToken) {
                log.info("[Server] Superseding in-flight run for session: {}", requestId);
                superseded.cancel();
            }

            // Set up SSE streaming response via raw servlet response
            HttpServletResponse res = ctx.res();
            res.setContentType("text/event-stream");
            res.setCharacterEncoding("UTF-8");
            res.setHeader("Cache-Control", "no-cache");
            res.setHeader("Connection", "keep-alive");
            res.setHeader("X-Accel-Buffering", "no");

            final String finalRawToken = rawToken;
            final String finalSessionId = sessionId;

            // Track sessionId alias for cleanup in finally block
            final java.util.concurrent.atomic.AtomicReference<String> resolvedSessionIdRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);

            // The id of the run currently owning this response, adopted from its START event.
            // Every later frame is stamped with it so a client can drop a superseded run's tail.
            final java.util.concurrent.atomic.AtomicReference<String> runIdRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);

            // Track whether the request completed normally (for auto-cancel on disconnect)
            final java.util.concurrent.atomic.AtomicBoolean completedNormally =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            // Tools are filtered before the run exists: a tool this tenant+identity may not
            // use is never handed to the model, so the agent cannot know it exists at all.
            AgentContext agentContext = toolPermissions.apply(toAgentContext(req));
            ThinkingLevel thinkingLevel = agentContext.thinkingLevel();
            String contextUserId = agentContext.userId();

            // Set credentials for HttpApiTool (user_passthrough auth)
            HttpApiTool.setCurrentCredentials(agentContext.credentials());

            try (OutputStream out = res.getOutputStream()) {
                SseStream sse = new SseStream(out, mapper, runIdRef);
                if (agentContext.isStreaming()) {
                    ScheduledFuture<?> keepalive =
                            startSseKeepalive(sse, cancellationToken, requestId);
                    try {
                        // Streaming mode: emit tokens as SSE events in real-time
                        agent.streamRun(finalRawToken, req.text(),
                                req.attachments() != null ? req.attachments() : Collections.emptyList(),
                                finalSessionId, req.systemPrompt(), cancellationToken,
                                event -> {
                                    // A tool that ignores cancellation (image generation, a long
                                    // download) reports back long after the run died. Its result
                                    // belongs to a run nobody is waiting for any more.
                                    if (isLateEventOfCancelledRun(cancellationToken, event)) {
                                        return;
                                    }
                                    try {
                                        switch (event.type()) {
                                            case START -> {
                                                // Register sessionId for cancellation
                                                String sid = (String) event.metadata().get("sessionId");
                                                if (sid != null && !sid.isEmpty() && !sid.equals(requestId)) {
                                                    activeRequests.put(sid, cancellationToken);
                                                    resolvedSessionIdRef.set(sid);
                                                }
                                                Object runId = event.metadata().get("runId");
                                                if (runId != null) {
                                                    runIdRef.set(runId.toString());
                                                }
                                                sse.emit("start", event.metadata());
                                            }
                                            case TOKEN -> sse.emit("token", Map.of("text", event.data()));
                                            case TOOL_CALL_CREATED -> sse.emit(
                                                    "tool_call_created", toolEventPayload(event, true));
                                            case TOOL_CALL_START -> sse.emit(
                                                    "tool_call_start", toolEventPayload(event, true));
                                            case TOOL_CALL_DONE -> sse.emit(
                                                    "tool_call_done", toolCompletionPayload(event));
                                            case TOOL_OUTPUT -> sse.emit(
                                                    "tool_output", ToolOutputSseMapper.toPayload(event));
                                            case SUBAGENT_STATUS -> sse.emit(
                                                    "subagent_status", event.metadata());
                                            case CONFIRMATION_REQUIRED -> sse.emit(
                                                    "confirmation_required", event.metadata());
                                            case CONFIRMATION_RESOLVED -> sse.emit(
                                                    "confirmation_resolved", event.metadata());
                                            case STEP -> {
                                                // ReActStep is serialized by Jackson, extract inspection from the map
                                                Object stepObj = event.metadata().get("step");
                                                String inspectionStatus = "PASS";
                                                if (stepObj instanceof java.util.Map<?,?> stepMap) {
                                                    Object insp = stepMap.get("inspection");
                                                    if (insp instanceof java.util.Map<?,?> inspMap) {
                                                        Object status = inspMap.get("status");
                                                        if (status != null) inspectionStatus = status.toString();
                                                    }
                                                } else if (stepObj instanceof com.harness.core.model.ReActStep step) {
                                                    var insp = step.inspection();
                                                    if (insp != null) inspectionStatus = insp.status().name();
                                                }
                                                sse.emit("step", Map.of("status", inspectionStatus));
                                            }
                                            case COMPRESS -> sse.emit("compress", Map.of(
                                                    "mode", event.metadata().get("mode"),
                                                    "detail", event.data()));
                                            case DONE -> {
                                                Map<String, Object> donePayload = new java.util.HashMap<>(event.metadata());
                                                donePayload.put("output", event.data() != null ? event.data() : "");
                                                sse.emit("done", donePayload);
                                                completedNormally.set(true);
                                            }
                                            case CANCELLED -> sse.emit("cancelled",
                                                    Map.of("message", event.data()));
                                            case ERROR -> sse.emit("error",
                                                    Map.of("error", event.data()));
                                        }
                                    } catch (IOException e) {
                                        log.debug("[Server] Failed to write SSE event: {}", e.getMessage());
                                        cancellationToken.cancel();
                                    }
                                }, thinkingLevel, contextUserId, agentContext);
                    } finally {
                        keepalive.cancel(false);
                    }
                } else {
                    // Blocking mode: run agent
                    AgentResult result = agent.run(finalRawToken, req.text(),
                            req.attachments() != null ? req.attachments() : Collections.emptyList(),
                            finalSessionId, req.systemPrompt(), cancellationToken, thinkingLevel, contextUserId, agentContext);

                    long duration = System.currentTimeMillis() - start;
                    log.info("[Server] Chat completed: traceId={}, steps={}, risk={}, duration={}ms",
                            result.trace().traceId(), result.steps().size(), result.riskLevel(), duration);

                    // Extract sessionId from result and send as first event
                    String resolvedSessionId = result.trace().metadata() != null
                            ? result.trace().metadata().getOrDefault("session_id", "")
                            : "";

                    // Register sessionId for cancellation (alias to same token)
                    if (!resolvedSessionId.isEmpty() && !resolvedSessionId.equals(requestId)) {
                        activeRequests.put(resolvedSessionId, cancellationToken);
                        resolvedSessionIdRef.set(resolvedSessionId);
                    }

                    // Emit START event with sessionId (first event for client)
                    sse.emit("start", Map.of("sessionId", resolvedSessionId));

                    Map<String, Object> doneData = new java.util.HashMap<>();
                    doneData.put("output", result.output() != null ? result.output() : "");
                    doneData.put("riskLevel", result.riskLevel().name());
                    doneData.put("requiresConfirmation", result.requiresConfirmation());
                    doneData.put("traceId", result.trace().traceId());
                    doneData.put("steps", result.steps().size());
                    doneData.put("sessionId", resolvedSessionId);
                    doneData.put("blocks", result.blocks());
                    if (!result.artifacts().isEmpty()) {
                        doneData.put("artifacts", result.artifacts().stream().map(a -> Map.of(
                                "id", a.id(),
                                "name", a.name(),
                                "type", a.type().name(),
                                "mimeType", a.mimeType() != null ? a.mimeType() : "",
                                "sizeBytes", a.sizeBytes(),
                                "downloadUrl", a.downloadUrl(),
                                "previewUrl", a.previewUrl()
                        )).toList());
                    }
                    sse.emit("done", doneData);
                    completedNormally.set(true);
                }

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                log.error("[Server] Chat error after {}ms: {}", duration, e.getMessage(), e);
                try {
                    String friendlyMsg = com.harness.agent.AgentOrchestrator.friendlyErrorMessage(e);
                    new SseStream(res.getOutputStream(), mapper, runIdRef)
                            .emit("error", Map.of("error", friendlyMsg));
                } catch (IOException ex) {
                    log.debug("[Server] Failed to write error event to stream: {}", ex.getMessage());
                }
            } finally {
                // Auto-cancel if client disconnected or request didn't complete normally
                if (!completedNormally.get()) {
                    log.info("[Server] Request did not complete normally, auto-cancelling: requestId={}", requestId);
                    cancellationToken.cancel();
                }

                // Runs after the try-with-resources has closed the response body. If the UI
                // looks stuck while this line is missing from the log, the SSE stream was
                // never closed on the wire, and the client is still waiting for EOF.
                log.info("[Server] SSE response stream closed: requestId={}, completedNormally={}, duration={}ms",
                        requestId, completedNormally.get(), System.currentTimeMillis() - start);

                HttpApiTool.clearCurrentCredentials();
                // Two-arg removal: a superseding run may already own these keys, and this run
                // finishing must not unregister the run that replaced it.
                activeRequests.remove(requestId, cancellationToken);
                String alias = resolvedSessionIdRef.get();
                if (alias != null) {
                    activeRequests.remove(alias, cancellationToken);
                }
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Server] Chat setup error after {}ms: {}", duration, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * The only writer for one SSE response. Serializes framing, and stamps every frame with
     * the run it belongs to so a client can discard the tail of a run it already superseded.
     */
    private static final class SseStream {
        private final OutputStream out;
        private final ObjectMapper mapper;
        private final java.util.concurrent.atomic.AtomicReference<String> runId;

        SseStream(
                OutputStream out,
                ObjectMapper mapper,
                java.util.concurrent.atomic.AtomicReference<String> runId
        ) {
            this.out = out;
            this.mapper = mapper;
            this.runId = runId;
        }

        void emit(String eventType, Map<String, ?> payload) throws IOException {
            Map<String, Object> body = new java.util.HashMap<>(payload);
            String currentRunId = runId.get();
            if (currentRunId != null && !currentRunId.isBlank()) {
                body.put("runId", currentRunId);
            }
            synchronized (out) {
                out.write(("event: " + eventType + "\n").getBytes(StandardCharsets.UTF_8));
                out.write(("data: " + mapper.writeValueAsString(body) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }

        void keepalive() throws IOException {
            synchronized (out) {
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /**
     * Emits transport-level heartbeats for the lifetime of the run. A long tool call
     * (image generation, document parsing) writes no business events for minutes, and
     * with no bytes on the wire the client cannot tell a busy server from a dead one —
     * it aborts, which then cancels the run. The comment frame is invisible to the
     * client's SSE parser, it only proves the stream is alive.
     */
    private ScheduledFuture<?> startSseKeepalive(
            SseStream sse, CancellationToken cancellationToken, String requestId) {
        long seconds = EnvConfig.get().getInt(EnvKey.SSE_KEEPALIVE_SECONDS, 15);
        return SSE_KEEPALIVE_EXECUTOR.scheduleAtFixedRate(() -> {
            if (cancellationToken.isCancelled()) {
                return;
            }
            try {
                sse.keepalive();
            } catch (IOException e) {
                // No retry: a failed write means the peer is gone, which is a cancel
                // signal, not a transient error. isCancelled() also guards the log so
                // a disconnect is reported once, not once per interval.
                if (!cancellationToken.isCancelled()) {
                    log.info("[Server] SSE keepalive detected client disconnect: requestId={}, cause={}",
                            requestId, e.getMessage());
                    cancellationToken.cancel();
                }
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    /**
     * A cancelled run may still receive tool output: ReAct emits it before its own
     * post-execution cancellation check, and a tool that cannot abort runs to completion.
     * Terminal frames stay so the client can still close the stream it opened.
     */
    private static boolean isLateEventOfCancelledRun(
            CancellationToken cancellationToken, StreamEvent event) {
        return cancellationToken.isCancelled()
                && event.type() != StreamEvent.Type.CANCELLED
                && event.type() != StreamEvent.Type.ERROR
                && event.type() != StreamEvent.Type.DONE;
    }

    private Map<String, Object> toolEventPayload(
            StreamEvent event, boolean includeArguments) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("toolCallId", event.metadata().get("toolCallId"));
        payload.put("toolName", event.metadata().get("toolName"));
        payload.put("status", event.metadata().get("status"));
        if (includeArguments) {
            payload.put("arguments", parseToolArguments(event.metadata().get("arguments")));
        }
        return payload;
    }

    private Map<String, Object> toolCompletionPayload(StreamEvent event) throws IOException {
        Map<String, Object> payload = toolEventPayload(event, false);
        payload.put("durationMs", event.metadata().get("durationMs"));
        payload.put("errorSummary", event.metadata().get("errorSummary"));
        return payload;
    }

    private JsonNode parseToolArguments(Object arguments) throws IOException {
        if (!(arguments instanceof String json)) {
            throw new IOException("Tool event arguments must be JSON text");
        }
        return mapper.readTree(json);
    }

    static AgentContext toAgentContext(ChatRequest request) {
        Map<String, Object> contextData = AgentContextRequestMapper.sanitize(request.context());

        if (request.graphScope() != null) {
            Map<String, Object> internalGraphContext = new HashMap<>();
            internalGraphContext.put("graphId", request.graphScope().graphId());
            internalGraphContext.put("schemaId", request.graphScope().schemaId());
            if (request.graphScope().subjectIds() != null
                    && !request.graphScope().subjectIds().isEmpty()) {
                internalGraphContext.put("subjectIds", request.graphScope().subjectIds());
            }
            contextData.put(AgentContext.KEY_GRAPH_REQUEST_CONTEXT, internalGraphContext);
        }
        return AgentContext.of(contextData);
    }

    public record ChatRequest(String text,
            List<MultimodalParser.RawAttachment> attachments,
            String systemPrompt,
            Map<String, Object> context,
            GraphScopeRequest graphScope) {
    }

    public record GraphScopeRequest(
            String graphId,
            String schemaId,
            Set<String> subjectIds) {
    }
}
