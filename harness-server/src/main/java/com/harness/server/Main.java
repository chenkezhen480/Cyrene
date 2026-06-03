package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.audit.store.TraceStore;
import com.harness.audit.store.TraceStoreFactory;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.CancellationToken;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.preprocess.knowledge.KnowledgeIngestService;
import com.harness.preprocess.rag.PgVectorRagRetriever;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP API server entry point for Harness Agent.
 * Run with: java -jar harness-server.jar
 *
 * Endpoints:
 *   POST   /api/auth/token        - Get JWT token (userId/username + password)
 *   POST   /api/chat              - Send a message, get agent response (SSE stream)
 *   DELETE /api/chat/{sessionId}  - Cancel an in-progress chat request
 *   POST   /api/sessions          - Create a new session
 *   GET    /api/sessions          - List sessions (cursor pagination, filter by userId/status)
 *   GET    /api/sessions/{id}     - Get session detail
 *   GET    /api/sessions/{id}/messages - Get session message history (cursor pagination)
 *   GET    /api/sessions/{id}/stats   - Get session statistics
 *   DELETE /api/sessions/{id}     - Close/delete a session
 *   POST   /api/knowledge/upload  - Upload file for knowledge base ingestion
 *   GET    /api/trace/{id}        - Get trace by ID
 *   GET    /api/traces            - List recent traces
 *   GET    /api/health            - Health check
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        EnvConfig.init(Collections.emptyMap());

        String authMode = EnvConfig.get().getString(EnvKey.AUTH_MODE, "none");
        boolean serverEnabled = EnvConfig.get().getBool(EnvKey.SERVER_ENABLED, true);

        // Auth mode and server are mutually bound:
        // - auth=token/jwt implies server must be running (auth endpoints are HTTP-only)
        // - server enabled + auth=none logs a warning
        if ("token".equals(authMode) || "jwt".equals(authMode)) {
            if (!serverEnabled) {
                log.warn("[Server] Auth mode '{}' requires server, forcing SERVER_ENABLED=true", authMode);
                serverEnabled = true;
            }
        }
        if (!serverEnabled) {
            log.info("Server disabled (HARNESS_SERVER_ENABLED=false), exiting");
            return;
        }
        if ("none".equals(authMode)) {
            log.warn("[Server] Auth disabled (mode=none), all requests will be anonymous");
        }

        String host = EnvConfig.get().getString(EnvKey.SERVER_HOST, "0.0.0.0");
        int port = EnvConfig.get().getInt(EnvKey.SERVER_PORT, 8080);

        AgentOrchestrator agent = new AgentOrchestrator();
        Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdown));

        // Knowledge base upload service
        PgVectorRagRetriever pgVector = new PgVectorRagRetriever(agent.embeddingModel());
        KnowledgeIngestService ingestService = new KnowledgeIngestService(agent.embeddingModel(), pgVector);
        TraceStore traceStore = TraceStoreFactory.create();

        // Shared cancellation token registry for in-flight chat requests
        ConcurrentHashMap<String, CancellationToken> activeRequests = new ConcurrentHashMap<>();

        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new io.javalin.json.JavalinJackson(mapper, false));
        }).start(host, port);

        // Health check
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "version", "0.1.0")));

        // Auth token endpoint
        if ("jwt".equals(authMode)) {
            AuthHandler authHandler = new AuthHandler();
            app.post("/api/auth/token", authHandler::handle);
            log.info("[Server] Auth endpoint registered: POST /api/auth/token (mode=jwt)");
        }

        // Knowledge base upload endpoint
        KnowledgeUploadHandler knowledgeHandler = new KnowledgeUploadHandler(ingestService, traceStore);
        app.post("/api/knowledge/upload", knowledgeHandler::handle);

        // Knowledge base management endpoints
        KnowledgeManagementHandler knowledgeMgmtHandler = new KnowledgeManagementHandler(pgVector);
        app.get("/api/knowledge/{collection}", knowledgeMgmtHandler::listDocuments);
        app.delete("/api/knowledge/{collection}", knowledgeMgmtHandler::deleteCollection);
        app.delete("/api/knowledge/{collection}/{documentId}", knowledgeMgmtHandler::deleteDocument);

        // Chat endpoint (SSE streaming)
        ChatHandler chatHandler = new ChatHandler(agent, activeRequests);
        app.post("/api/chat", chatHandler::handle);

        // Session management endpoints
        SessionHandler sessionHandler = new SessionHandler(agent.sessionStore(), agent.messageStore(), agent.messageCache());
        app.post("/api/sessions", sessionHandler::create);
        app.get("/api/sessions", sessionHandler::list);
        app.get("/api/sessions/{sessionId}", sessionHandler::detail);
        app.get("/api/sessions/{sessionId}/messages", sessionHandler::messages);
        app.get("/api/sessions/{sessionId}/stats", sessionHandler::stats);
        app.delete("/api/sessions/{sessionId}", sessionHandler::delete);

        // Cancel in-progress chat request
        app.delete("/api/chat/{sessionId}", ctx -> {
            String sessionId = ctx.pathParam("sessionId");
            CancellationToken token = activeRequests.get(sessionId);
            if (token == null) {
                ctx.status(404).json(Map.of("error", "No active request found for session: " + sessionId));
                return;
            }
            token.cancel();
            log.info("[Server] Cancellation requested for session: {}", sessionId);
            ctx.json(Map.of("status", "cancelled", "sessionId", sessionId));
        });

        // Get trace by ID
        app.get("/api/trace/{id}", ctx -> {
            String traceId = ctx.pathParam("id");
            Optional<AgentTrace> trace = traceStore.findById(traceId);
            if (trace.isPresent()) {
                ctx.json(trace.get());
            } else {
                ctx.status(404).json(Map.of("error", "Trace not found: " + traceId));
            }
        });

        // List recent traces
        app.get("/api/traces", ctx -> {
            String limitParam = ctx.queryParam("limit");
            int limit = 20; // default
            if (limitParam != null) {
                try {
                    limit = Integer.parseInt(limitParam);
                } catch (NumberFormatException e) {
                    ctx.status(400).json(Map.of("error", "Invalid limit parameter: " + limitParam));
                    return;
                }
            }
            List<AgentTrace> traces = traceStore.listRecent(limit);
            ctx.json(traces);
        });

        log.info("Harness Server started on {}:{}", host, port);
    }
}
