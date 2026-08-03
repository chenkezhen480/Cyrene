package com.harness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.audit.store.AuditCleanupScheduler;
import com.harness.audit.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.CancellationToken;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.graph.build.GraphBuildService;
import com.harness.agent.graph.LlmGraphDataConverter;
import com.harness.graph.build.GraphDataConverterRegistry;
import com.harness.preprocess.knowledge.KnowledgeIngestService;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.server.log.LogStorageService;
import io.javalin.Javalin;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP API server entry point for Harness Agent.
 * Run with: java -jar harness-server.
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
    private static final String VERSION = resolveVersion();

    private static String resolveVersion() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    public static void main(String[] args) {
        // Use our cancellable HTTP client (supports request cancellation for token savings)
        System.setProperty("langchain4j.http.clientBuilderFactory",
                "com.harness.ai.model.impl.CancellableHttpClient$Factory");

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
            log.info("Server disabled ({}=false), exiting", EnvKey.SERVER_ENABLED);
            return;
        }
        if ("none".equals(authMode)) {
            log.warn("[Server] Auth disabled (mode=none), all requests will be anonymous");
        }

        String host = EnvConfig.get().getString(EnvKey.SERVER_HOST, "0.0.0.0");
        int port = EnvConfig.get().getInt(EnvKey.SERVER_PORT, 8080);
        int workers = Math.max(EnvConfig.get().getInt(EnvKey.SERVER_WORKERS, Runtime.getRuntime().availableProcessors() * 2), 8);

        AgentOrchestrator agent = new AgentOrchestrator();
        Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdown));

        // Knowledge base upload service — reuse agent's instances
        KnowledgeIngestService ingestService = new KnowledgeIngestService(agent.embeddingModel(), agent.vectorStore());
        TraceStore traceStore = agent.traceStore();

        // Shared cancellation token registry for in-flight chat requests
        ConcurrentHashMap<String, CancellationToken> activeRequests = new ConcurrentHashMap<>();

        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Audit cleanup scheduler
        AuditCleanupScheduler auditCleanup = new AuditCleanupScheduler(traceStore);
        auditCleanup.start();
        Runtime.getRuntime().addShutdownHook(new Thread(auditCleanup::stop));

        // Log storage: buffer WARN/ERROR, flush every 1h + on shutdown
        LogStorageService logStorage = new LogStorageService();
        logStorage.start();

        QueuedThreadPool pool = new QueuedThreadPool(workers, workers, 60000);
        pool.setName("harness-server");

        int idleTimeoutMs = EnvConfig.get().getInt(EnvKey.SERVER_IDLE_TIMEOUT, 300_000); // default 5 min
        long maxRequestSize = EnvConfig.get().getLong(EnvKey.SERVER_MAX_REQUEST_SIZE_MB, 20) * 1024 * 1024;
        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new io.javalin.json.JavalinJackson(mapper, false));
            config.http.maxRequestSize = maxRequestSize;
            config.jetty.threadPool = pool;
            config.jetty.modifyServer(server -> {
                for (org.eclipse.jetty.server.Connector c : server.getConnectors()) {
                    if (c instanceof org.eclipse.jetty.server.ServerConnector sc) {
                        sc.setIdleTimeout(idleTimeoutMs);
                    }
                }
            });
            config.staticFiles.add("/public", io.javalin.http.staticfiles.Location.CLASSPATH);
            // 提供 knowledge-uploads 目录下的文件访问（用于图生图等场景）
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/files";
                staticFiles.directory = EnvConfig.get().getString(EnvKey.KNOWLEDGE_UPLOAD_DIR, "./knowledge-uploads");
                staticFiles.location = io.javalin.http.staticfiles.Location.EXTERNAL;
            });
        }).start(host, port);

        // Health check
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "version", VERSION)));

        // Auth token endpoint
        if ("jwt".equals(authMode)) {
            AuthHandler authHandler = new AuthHandler();
            app.post("/api/auth/token", authHandler::handle);
        }

        // Knowledge base upload endpoint
        KnowledgeUploadHandler knowledgeHandler = new KnowledgeUploadHandler(ingestService, traceStore);
        app.post("/api/knowledge/upload", knowledgeHandler::handle);

        // File upload endpoint (for image-to-image and other file references)
        String knowledgeUploadDir = EnvConfig.get().getString(EnvKey.KNOWLEDGE_UPLOAD_DIR, "./knowledge-uploads");
        FileUploadHandler fileUploadHandler = new FileUploadHandler(knowledgeUploadDir);
        app.post("/api/files/upload", fileUploadHandler::handle);

        // Knowledge base management endpoints
        KnowledgeManagementHandler knowledgeMgmtHandler = new KnowledgeManagementHandler(agent.vectorStore());
        app.get("/api/knowledge/{collection}", knowledgeMgmtHandler::listDocuments);
        // List all knowledge collections
        app.get("/api/knowledge", ctx -> {
            List<String> collections = agent.vectorStore().listCollections();
            ctx.json(Map.of("collections", collections));
        });
        app.get("/api/knowledge/{collection}/{documentId}", knowledgeMgmtHandler::getDocument);
        app.put("/api/knowledge/{collection}/{documentId}", knowledgeMgmtHandler::updateDocument);
        app.delete("/api/knowledge/{collection}", knowledgeMgmtHandler::deleteCollection);
        app.delete("/api/knowledge/{collection}/{documentId}", knowledgeMgmtHandler::deleteDocument);

        // Structured knowledge graph endpoints (independent from vector RAG)
        GraphRequestExecutor graphRequestExecutor =
                new GraphRequestExecutor(new GraphRequestAuthenticator());
        GraphManagementHandler graphHandler = new GraphManagementHandler(
                agent.knowledgeGraphStore(),
                agent.graphSchemaRegistry(),
                agent.graphSettings(),
                agent.graphSpaceAccessService(),
                graphRequestExecutor
        );
        GraphDataConverterRegistry graphDataConverterRegistry =
                GraphDataConverterRegistry.withDefaults(mapper);
        graphDataConverterRegistry.register(new LlmGraphDataConverter(
                agent.chatModel(),
                agent.knowledgeGraphStore(),
                agent.graphSchemaRegistry(),
                agent.graphSettings(),
                mapper
        ));
        GraphBuildService graphBuildService = new GraphBuildService(
                agent.knowledgeGraphStore(), graphDataConverterRegistry);
        GraphBuildHandler graphBuildHandler =
                new GraphBuildHandler(graphBuildService, graphRequestExecutor);
        GraphSchemaManagementHandler graphSchemaHandler = new GraphSchemaManagementHandler(
                agent.graphSchemaManagementService(),
                agent.knowledgeGraphStore(),
                agent.graphSettings(),
                graphRequestExecutor
        );
        app.get("/api/graph/status", graphHandler::status);
        app.get("/api/graph/graphs", graphHandler::listGraphSpaces);
        app.delete("/api/graph/graphs", graphHandler::deleteGraphSpace);
        app.get("/api/graph/schemas", graphHandler::listSchemas);
        app.get("/api/graph/schemas/{schemaId}", graphHandler::getSchema);
        app.get("/api/graph/schema-configs", graphSchemaHandler::list);
        app.get("/api/graph/schema-configs/{schemaId}", graphSchemaHandler::get);
        app.post("/api/graph/schema-configs", graphSchemaHandler::create);
        app.put("/api/graph/schema-configs/{schemaId}", graphSchemaHandler::update);
        app.post("/api/graph/schema-configs/{schemaId}/enable", graphSchemaHandler::enable);
        app.post("/api/graph/schema-configs/{schemaId}/disable", graphSchemaHandler::disable);
        app.delete("/api/graph/schema-configs/{schemaId}", graphSchemaHandler::delete);
        app.post("/api/graph/build/preview", graphBuildHandler::preview);
        app.post("/api/graph/build", graphBuildHandler::build);
        app.post("/api/graph/mutations", graphHandler::mutate);
        app.post("/api/graph/nodes/batch", graphHandler::upsertNodes);
        app.get("/api/graph/nodes", graphHandler::listNodes);
        app.get("/api/graph/nodes/{nodeId}", graphHandler::getNode);
        app.delete("/api/graph/nodes/{nodeId}", graphHandler::deleteNode);
        app.post("/api/graph/relations/batch", graphHandler::upsertRelations);
        app.get("/api/graph/relations", graphHandler::listRelations);
        app.delete("/api/graph/relations/{relationId}", graphHandler::deleteRelation);
        app.delete("/api/graph/sources/{sourceId}", graphHandler::deleteSource);
        app.post("/api/graph/query", graphHandler::query);

        // Chat endpoint (SSE streaming)
        ChatHandler chatHandler = new ChatHandler(agent, activeRequests);
        app.post("/api/chat", chatHandler::handle);

        ConfirmationHandler confirmationHandler =
                new ConfirmationHandler(agent.confirmationManager());
        app.post("/api/confirmations/{requestId}/approve", confirmationHandler::approve);
        app.post("/api/confirmations/{requestId}/reject", confirmationHandler::reject);

        // Global exception handler: format framework-level errors (e.g. body too large) as SSE for chat
        app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> {
            String accept = ctx.header("Accept");
            if (accept != null && accept.contains("text/event-stream")) {
                ctx.contentType("text/event-stream");
                try {
                    ctx.result("event: error\ndata: " + mapper
                            .writeValueAsString(Map.of("error", e.getMessage())) + "\n\n");
                } catch (JsonProcessingException ex) {
                    throw new RuntimeException(ex);
                }
            } else {
                ApiResponses.error(
                        ctx,
                        e.getStatus(),
                        ApiErrorCode.fromHttpStatus(e.getStatus()),
                        e.getMessage());
            }
        });

        // Session management endpoints
        SessionHandler sessionHandler = new SessionHandler(agent.sessionStore(), agent.messageStore(), agent.messageCache(), agent.messageWriteWorker());
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
                ApiResponses.error(ctx, 404, ApiErrorCode.NOT_FOUND,
                        "No active request found for session: " + sessionId);
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
                ApiResponses.error(
                        ctx, 404, ApiErrorCode.NOT_FOUND, "Trace not found: " + traceId);
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
                    ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                            "Invalid limit parameter: " + limitParam);
                    return;
                }
            }
            List<AgentTrace> traces = traceStore.listRecent(limit);
            ctx.json(traces);
        });

        // Trace stats
        int retentionDays = EnvConfig.get().getInt(EnvKey.AUDIT_RETENTION_DAYS, 30);
        app.get("/api/traces/stats", ctx -> {
            ctx.json(Map.of("count", traceStore.count(), "retentionDays", retentionDays));
        });

        // Manual trace cleanup
        app.delete("/api/traces/cleanup", ctx -> {
            int deleted = traceStore.cleanup(retentionDays);
            ctx.json(Map.of("deleted", deleted, "retentionDays", retentionDays));
        });

        // Delete specific trace
        app.delete("/api/traces/{traceId}", ctx -> {
            String traceId = ctx.pathParam("traceId");
            boolean deleted = traceStore.deleteById(traceId);
            if (deleted) {
                ctx.json(Map.of("status", "deleted", "traceId", traceId));
            } else {
                ApiResponses.error(
                        ctx, 404, ApiErrorCode.NOT_FOUND, "Trace not found: " + traceId);
            }
        });

        // Artifact download/preview endpoints
        if (agent.artifactStore() != null) {
            ArtifactHandler artifactHandler = new ArtifactHandler(agent.artifactStore());
            app.get("/api/artifacts/{id}", artifactHandler::download);
            app.get("/api/artifacts/{id}/preview", artifactHandler::preview);
            app.get("/api/artifacts/session/{sessionId}", artifactHandler::listBySession);
        }

        // Project API Discovery endpoints
        ProjectDiscoveryHandler discoveryHandler = new ProjectDiscoveryHandler(mapper, agent);
        app.post("/api/project-discovery/scan", discoveryHandler::scan);
        app.post("/api/project-discovery/generate", discoveryHandler::generate);
        app.get("/api/project-discovery/config", discoveryHandler::getConfig);
        app.put("/api/project-discovery/config", discoveryHandler::updateConfig);
        app.post("/api/project-discovery/reload", discoveryHandler::reload);

        log.info("Harness Server started on {}:{} (workers={}, idleTimeout={}s)", host, port, workers, idleTimeoutMs / 1000);

        // First-launch detection: open browser if no project-apis.json exists in local env
        if (EnvConfig.get().getBool(EnvKey.PROJECT_DISCOVERY_ENABLED, true)) {
            String apisConfigPath = EnvConfig.get().getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
            Path apisConfig = Path.of(apisConfigPath);
            if (!Files.exists(apisConfig) && isLocalEnvironment()) {
                tryOpenBrowser("http://localhost:" + port);
            }
        }
    }

    private static boolean isLocalEnvironment() {
        try {
            return java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
                    && !Files.exists(Path.of("/.dockerenv"))
                    && System.getenv("KUBERNETES_SERVICE_HOST") == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void tryOpenBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            log.info("[Server] Opened browser for first-launch setup: {}", url);
        } catch (Exception e) {
            log.debug("[Server] Auto-open browser failed (non-fatal): {}", e.getMessage());
        }
    }
}
