package com.harness.server;

import com.harness.agent.AgentOrchestrator;
import com.harness.core.model.AgentResult;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.input.auth.JwtUtil;
import com.harness.input.multimodal.MultimodalParser;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ChatHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private final AgentOrchestrator agent;
    private final String authMode;
    private final JwtUtil jwtUtil;

    public ChatHandler(AgentOrchestrator agent) {
        this.agent = agent;
        this.authMode = EnvConfig.get().getString(EnvKey.AUTH_MODE, "none");
        this.jwtUtil = "jwt".equals(authMode) ? new JwtUtil() : null;
        log.info("[Server] ChatHandler initialized: authMode={}", authMode);
    }

    public void handle(Context ctx) {
        long start = System.currentTimeMillis();
        try {
            ChatRequest req = ctx.bodyAsClass(ChatRequest.class);
            int attachCount = req.attachments() != null ? req.attachments().size() : 0;
            String sessionId = ctx.header("X-Session-Id");
            log.info("[Server] POST /api/chat: textLen={}, sessionId={}, attachments={}",
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
                    String userId = jwtUtil.verifyToken(rawToken);
                    log.info("[Server] JWT verified: userId={}", userId);
                } catch (Exception e) {
                    log.warn("[Server] JWT verification failed: {}", e.getMessage());
                    ctx.status(401).json(Map.of("error", "Invalid token: " + e.getMessage()));
                    return;
                }
            }

            AgentResult result = agent.run(rawToken, req.text(),
                    req.attachments() != null ? req.attachments() : Collections.emptyList(), sessionId);

            long duration = System.currentTimeMillis() - start;
            log.info("[Server] Chat completed: traceId={}, steps={}, risk={}, duration={}ms",
                    result.trace().traceId(), result.steps().size(), result.riskLevel(), duration);

            ctx.json(Map.of(
                    "output", result.output(),
                    "riskLevel", result.riskLevel().name(),
                    "traceId", result.trace().traceId(),
                    "steps", result.steps().size(),
                    "sessionId", result.trace().metadata() != null
                            ? result.trace().metadata().getOrDefault("session_id", "")
                            : ""
            ));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Server] Chat error after {}ms: {}", duration, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    public record ChatRequest(String text,
            List<MultimodalParser.RawAttachment> attachments) {
    }
}
