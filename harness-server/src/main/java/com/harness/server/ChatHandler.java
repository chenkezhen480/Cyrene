package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.ai.model.impl.CancellableHttpClient;
import com.harness.core.model.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.input.auth.JwtUtil;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.tool.HttpApiTool;
import io.javalin.http.Context;
import io.jsonwebtoken.Claims;
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

public class ChatHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private final AgentOrchestrator agent;
    private final String authMode;
    private final JwtUtil jwtUtil;
    private final ConcurrentHashMap<String, CancellationToken> activeRequests;
    private final ObjectMapper mapper;
    private final int refreshThresholdMinutes;

    public ChatHandler(AgentOrchestrator agent, ConcurrentHashMap<String, CancellationToken> activeRequests) {
        this.agent = agent;
        this.activeRequests = activeRequests;
        this.authMode = EnvConfig.get().getString(EnvKey.AUTH_MODE, "none");
        this.jwtUtil = "jwt".equals(authMode) ? new JwtUtil() : null;
        this.mapper = new ObjectMapper();
        this.refreshThresholdMinutes = EnvConfig.get().getInt(EnvKey.AUTH_JWT_REFRESH_THRESHOLD_MINUTES, 60);
        log.info("[Server] ChatHandler initialized: authMode={}, refreshThreshold={}min", authMode, refreshThresholdMinutes);
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

            // Auth gate: skip if mode=none, validate JWT if mode=jwt
            String rawToken = null;
            if ("jwt".equals(authMode)) {
                String authHeader = ctx.header("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    log.warn("[Server] Missing or invalid Authorization header");
                    ApiResponses.error(
                            ctx, 401, ApiErrorCode.UNAUTHORIZED, "Missing Bearer token");
                    return;
                }
                rawToken = authHeader.substring(7);
                try {
                    Claims claims = jwtUtil.verifyTokenClaims(rawToken);
                    String userId = claims.getSubject();
                    log.debug("[Server] JWT verified: userId={}", userId);

                    // Sliding window refresh: if remaining lifetime < threshold, issue new token
                    if (jwtUtil.shouldRefresh(claims, refreshThresholdMinutes)) {
                        String newToken = jwtUtil.refreshToken(userId);
                        ctx.header("X-New-Token", newToken);
                        log.info("[Server] JWT refreshed for userId={}", userId);
                    }
                } catch (Exception e) {
                    log.warn("[Server] JWT verification failed: {}", e.getMessage());
                    ApiResponses.error(ctx, 401, ApiErrorCode.UNAUTHORIZED,
                            "Invalid token: " + e.getMessage());
                    return;
                }
            }

            // Register cancellation token
            String requestId = sessionId != null ? sessionId : java.util.UUID.randomUUID().toString();
            CancellationToken cancellationToken = new CancellationToken();
            cancellationToken.onCancel(CancellableHttpClient::cancelAll);
            activeRequests.put(requestId, cancellationToken);

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

            // Track whether the request completed normally (for auto-cancel on disconnect)
            final java.util.concurrent.atomic.AtomicBoolean completedNormally =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            AgentContext agentContext = toAgentContext(req);
            Boolean enableThinking = agentContext.enableThinking();
            String contextUserId = agentContext.userId();

            // Set credentials for HttpApiTool (user_passthrough auth)
            HttpApiTool.setCurrentCredentials(agentContext.credentials());

            try (OutputStream out = res.getOutputStream()) {
                if (agentContext.isStreaming()) {
                    // Streaming mode: emit tokens as SSE events in real-time
                    agent.streamRun(finalRawToken, req.text(),
                            req.attachments() != null ? req.attachments() : Collections.emptyList(),
                            finalSessionId, req.systemPrompt(), cancellationToken,
                            event -> {
                                try {
                                    switch (event.type()) {
                                        case START -> {
                                            // Register sessionId for cancellation
                                            String sid = (String) event.metadata().get("sessionId");
                                            if (sid != null && !sid.isEmpty() && !sid.equals(requestId)) {
                                                activeRequests.put(sid, cancellationToken);
                                                resolvedSessionIdRef.set(sid);
                                            }
                                            writeSseEvent(out, "start",
                                                    mapper.writeValueAsString(event.metadata()));
                                        }
                                        case TOKEN -> writeSseEvent(out, "token",
                                                mapper.writeValueAsString(Map.of("text", event.data())));








                                        case TOOL_CALL_CREATED -> writeSseEvent(out, "tool_call_created",
                                                mapper.writeValueAsString(Map.of(
                                                        "toolName", event.metadata().get("toolName"),
                                                        "arguments", event.metadata().get("arguments"))));
                                        case TOOL_CALL_START -> writeSseEvent(out, "tool_call_start",
                                                mapper.writeValueAsString(Map.of(
                                                        "toolName", event.metadata().get("toolName"),
                                                        "arguments", event.metadata().get("arguments"))));
                                        case TOOL_CALL_DONE -> writeSseEvent(out, "tool_call_done",
                                                mapper.writeValueAsString(Map.of(
                                                        "toolName", event.metadata().get("toolName"),
                                                        "success", event.metadata().get("success"),
                                                        "durationMs", event.metadata().get("durationMs"))));
                                        case CONFIRMATION_REQUIRED -> writeSseEvent(
                                                out,
                                                "confirmation_required",
                                                mapper.writeValueAsString(event.metadata()));
                                        case CONFIRMATION_RESOLVED -> writeSseEvent(
                                                out,
                                                "confirmation_resolved",
                                                mapper.writeValueAsString(event.metadata()));
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
                                            writeSseEvent(out, "step",
                                                    mapper.writeValueAsString(Map.of("status", inspectionStatus)));
                                        }
                                        case COMPRESS -> writeSseEvent(out, "compress",
                                                mapper.writeValueAsString(Map.of(
                                                        "mode", event.metadata().get("mode"),
                                                        "detail", event.data())));
                                        case ARTIFACT -> writeSseEvent(out, "artifact",
                                                mapper.writeValueAsString(event.metadata()));
                                        case DONE -> {
                                            Map<String, Object> donePayload = new java.util.HashMap<>(event.metadata());
                                            donePayload.put("output", event.data() != null ? event.data() : "");
                                            writeSseEvent(out, "done", mapper.writeValueAsString(donePayload));
                                            completedNormally.set(true);
                                        }
                                        case CANCELLED -> writeSseEvent(out, "cancelled",
                                                mapper.writeValueAsString(Map.of("message", event.data())));
                                        case ERROR -> writeSseEvent(out, "error",
                                                mapper.writeValueAsString(Map.of("error", event.data())));
                                    }
                                } catch (IOException e) {
                                    log.debug("[Server] Failed to write SSE event: {}", e.getMessage());
                                    cancellationToken.cancel();
                                }
                            }, enableThinking, contextUserId, agentContext);
                } else {
                    // Blocking mode: run agent
                    AgentResult result = agent.run(finalRawToken, req.text(),
                            req.attachments() != null ? req.attachments() : Collections.emptyList(),
                            finalSessionId, req.systemPrompt(), cancellationToken, enableThinking, contextUserId, agentContext);

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
                    writeSseEvent(out, "start", mapper.writeValueAsString(Map.of(
                            "sessionId", resolvedSessionId)));

                    Map<String, Object> doneData = new java.util.HashMap<>();
                    doneData.put("output", result.output() != null ? result.output() : "");
                    doneData.put("riskLevel", result.riskLevel().name());
                    doneData.put("requiresConfirmation", result.requiresConfirmation());
                    doneData.put("traceId", result.trace().traceId());
                    doneData.put("steps", result.steps().size());
                    doneData.put("sessionId", resolvedSessionId);
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
                    writeSseEvent(out, "done", mapper.writeValueAsString(doneData));
                    completedNormally.set(true);
                }

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                log.error("[Server] Chat error after {}ms: {}", duration, e.getMessage(), e);
                try {
                    OutputStream out = res.getOutputStream();
                    String friendlyMsg = com.harness.agent.AgentOrchestrator.friendlyErrorMessage(e);
                    Map<String, String> errorData = Map.of("error", friendlyMsg);
                    writeSseEvent(out, "error", mapper.writeValueAsString(errorData));
                } catch (IOException ex) {
                    log.debug("[Server] Failed to write error event to stream: {}", ex.getMessage());
                }
            } finally {
                // Auto-cancel if client disconnected or request didn't complete normally
                if (!completedNormally.get()) {
                    log.info("[Server] Request did not complete normally, auto-cancelling: requestId={}", requestId);
                    cancellationToken.cancel();
                }

                HttpApiTool.clearCurrentCredentials();
                activeRequests.remove(requestId);
                String alias = resolvedSessionIdRef.get();
                if (alias != null) {
                    activeRequests.remove(alias);
                }
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Server] Chat setup error after {}ms: {}", duration, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    private void writeSseEvent(OutputStream out, String eventType, String data) throws IOException {
        out.write(("event: " + eventType + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static AgentContext toAgentContext(ChatRequest request) {
        Map<String, Object> contextData = new HashMap<>(
                request.context() != null ? request.context() : Map.of());
        contextData.remove(AgentContext.KEY_GRAPH_REQUEST_CONTEXT);
        contextData.remove(AgentContext.KEY_NEEDS_GRAPH_KNOWLEDGE);

        if (request.graphScope() != null) {
            Map<String, Object> internalGraphContext = new HashMap<>();
            internalGraphContext.put("graphId", request.graphScope().graphId());
            internalGraphContext.put("schemaId", request.graphScope().schemaId());
            internalGraphContext.put("subjectIds", request.graphScope().subjectIds());
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
