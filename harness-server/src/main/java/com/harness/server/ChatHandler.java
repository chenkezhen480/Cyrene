package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.core.model.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.input.auth.JwtUtil;
import com.harness.input.multimodal.MultimodalParser;
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
import java.util.List;
import java.util.Map;
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

            // Auth gate: skip if mode=none, validate JWT if mode=jwt
            String rawToken = null;
            if ("jwt".equals(authMode)) {
                String authHeader = ctx.header("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    log.warn("[Server] Missing or invalid Authorization header");
                    ctx.status(401).json(Map.of("error", "Missing Bearer token"));
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
                    ctx.status(401).json(Map.of("error", "Invalid token: " + e.getMessage()));
                    return;
                }
            }

            // Register cancellation token
            String requestId = sessionId != null ? sessionId : java.util.UUID.randomUUID().toString();
            CancellationToken cancellationToken = new CancellationToken();
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

            AgentContext agentContext = AgentContext.of(req.context());
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
                                        case STEP -> {
                                            ReActStep step = (ReActStep) event.metadata().get("step");
                                            writeSseEvent(out, "step", mapper.writeValueAsString(Map.of(
                                                    "stepNumber", step.stepNumber(),
                                                    "action", step.action(),
                                                    "toolCalls", step.toolCalls().stream().map(ToolCall::toolName).toList()
                                            )));
                                        }
                                        case COMPRESS -> writeSseEvent(out, "compress",
                                                mapper.writeValueAsString(Map.of(
                                                        "mode", event.metadata().get("mode"),
                                                        "detail", event.data())));
                                        case DONE -> writeSseEvent(out, "done",
                                                mapper.writeValueAsString(event.metadata()));
                                        case ERROR -> writeSseEvent(out, "error",
                                                mapper.writeValueAsString(Map.of("error", event.data())));
                                    }
                                } catch (IOException e) {
                                    log.debug("[Server] Failed to write SSE event: {}", e.getMessage());
                                }
                            }, enableThinking, contextUserId);
                } else {
                    // Blocking mode: run agent
                    AgentResult result = agent.run(finalRawToken, req.text(),
                            req.attachments() != null ? req.attachments() : Collections.emptyList(),
                            finalSessionId, req.systemPrompt(), cancellationToken, enableThinking, contextUserId);

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

                    Map<String, Object> doneData = Map.of(
                            "output", result.output() != null ? result.output() : "",
                            "riskLevel", result.riskLevel().name(),
                            "traceId", result.trace().traceId(),
                            "steps", result.steps().size(),
                            "sessionId", resolvedSessionId
                    );
                    writeSseEvent(out, "done", mapper.writeValueAsString(doneData));
                }

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                log.error("[Server] Chat error after {}ms: {}", duration, e.getMessage(), e);
                try {
                    OutputStream out = res.getOutputStream();
                    Map<String, String> errorData = Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
                    writeSseEvent(out, "error", mapper.writeValueAsString(errorData));
                } catch (IOException ex) {
                    log.debug("[Server] Failed to write error event to stream: {}", ex.getMessage());
                }
            } finally {
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
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void writeSseEvent(OutputStream out, String eventType, String data) throws IOException {
        out.write(("event: " + eventType + "\n").getBytes(StandardCharsets.UTF_8));
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public record ChatRequest(String text,
            List<MultimodalParser.RawAttachment> attachments,
            String systemPrompt,
            java.util.Map<String, Object> context) {
    }
}
