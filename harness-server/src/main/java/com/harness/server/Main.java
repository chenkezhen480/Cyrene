package com.harness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.audit.store.TraceStore;
import com.harness.audit.store.TraceStoreFactory;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.preprocess.knowledge.KnowledgeIngestService;
import com.harness.preprocess.rag.PgVectorRagRetriever;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * HTTP API server entry point for Harness Agent.
 * Run with: java -jar harness-server.jar
 *
 * Endpoints:
 *   POST /api/auth/token  - Get JWT token (userId/username + password)
 *   POST /api/chat        - Send a message, get agent response
 *   POST /api/knowledge/upload - Upload file for knowledge base ingestion
 *   GET  /api/trace/:id   - Get trace by ID
 *   GET  /api/traces      - List recent traces
 *   GET  /api/health      - Health check
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

        // Chat endpoint
        ChatHandler chatHandler = new ChatHandler(agent);
        app.post("/api/chat", chatHandler::handle);

        // Get trace
        app.get("/api/trace/{id}", ctx -> {
            // TODO: Wire to TraceStore.findById
            ctx.json(Map.of("message", "Trace lookup not yet wired"));
        });

        // List traces
        app.get("/api/traces", ctx -> {
            // TODO: Wire to TraceStore.listRecent
            ctx.json(Map.of("message", "Trace listing not yet wired"));
        });

        log.info("Harness Server started on {}:{}", host, port);
    }
}
