package com.harness.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.AgentOrchestrator;
import com.harness.trace.store.AuditCleanupScheduler;
import com.harness.trace.store.TraceStore;
import com.harness.core.model.AgentTrace;
import com.harness.core.model.AgentContext;
import com.harness.core.model.CancellationToken;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.modelconfig.ModelConfigFile;
import com.harness.graph.build.GraphBuildService;
import com.harness.graph.build.GraphMutationCommitter;
import com.harness.agent.graph.LlmGraphDataConverter;
import com.harness.graph.build.GraphDataConverterRegistry;
import com.harness.tool.knowledge.PersistentGraphSchemaWikiCompiler;
import com.harness.tool.knowledge.PersistentGraphSpaceWikiCompiler;
import com.harness.tool.knowledge.GraphCapabilityDescriber;
import com.harness.tool.knowledge.GraphSchemaWikiCompiler;
import com.harness.tool.knowledge.GraphSpaceWikiCompiler;
import com.harness.tool.knowledge.authority.MysqlKnowledgeArtifactRepository;
import com.harness.tool.knowledge.authority.MysqlKnowledgeIndexOutboxStore;
import com.harness.tool.knowledge.okf.OkfBundleExporter;
import com.harness.tool.knowledge.okf.OkfImportService;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.server.log.LogStorageService;
import io.javalin.Javalin;
import com.harness.server.security.*;
import com.harness.core.security.ApiEndpointDescriptor;
import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
 *   POST   /api/structured-output - Run agent and return validated JSON
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
                "com.harness.provider.impl.CancellableHttpClient$Factory");

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

        AgentOrchestrator agent;
        try {
            agent = new AgentOrchestrator();
        } catch (RuntimeException startupFailure) {
            log.error("[Server] 应用启动失败：{}", startupFailure.getMessage());
            throw startupFailure;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdown));

        String knowledgeUploadDir = EnvConfig.get().getString(
                EnvKey.KNOWLEDGE_UPLOAD_DIR, "./knowledge-uploads");
        MysqlKnowledgeArtifactRepository knowledgeArtifactRepository =
                new MysqlKnowledgeArtifactRepository();
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
        InternalApiRouteRegistry apiRoutes = new InternalApiRouteRegistry();
        RequestPrincipalResolver requestPrincipals = new RequestPrincipalResolver(EnvConfig.get());
        ApiRequestAuthenticator requestAuthenticator = new ApiRequestAuthenticator(requestPrincipals);
        MysqlInternalApiPermissionStore apiPermissionStore = new MysqlInternalApiPermissionStore(
                com.harness.core.env.MysqlConnectionPool::getConnection);
        InternalApiPermissionService apiPermissions = new InternalApiPermissionService(
                apiPermissionStore, apiRoutes,
                EnvConfig.get().getBool(EnvKey.INTERNAL_API_AUTHORIZATION_ENABLED, true),
                EnvConfig.get().getString(EnvKey.INTERNAL_API_ADMIN_TENANT_ID, AgentContext.DEFAULT_TENANT_ID));
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
        });
        for (String path : List.of("/", "/index.html", "/js/api.js", "/js/app.js", "/js/i18n.js",
                "/js/sse-parser.js", "/js/tool-call-state.js", "/css/style.css")) {
            apiRoutes.register(new ApiEndpointDescriptor("static:" + path, path, "GET", path, "ui", PUBLIC));
        }
        FileUploadHandler fileUploadHandler = new FileUploadHandler(knowledgeUploadDir);
        InternalApiResourceAuthorizer apiResources = new InternalApiResourceAuthorizer(
                agent.sessionStore(), traceStore, agent.artifactStore(), fileUploadHandler);
        app.beforeMatched(ctx -> apiRoutes.check(ctx, requestPrincipals, (principal, endpoint) -> {
            apiPermissions.authorize(principal, endpoint);
            apiResources.authorize(ctx, principal, endpoint);
        }));
        app.exception(ApiRequestAuthenticator.RequestAuthenticationException.class,
                (e, ctx) -> ApiResponses.error(ctx, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage()));
        app.exception(SecurityException.class,
                (e, ctx) -> ApiResponses.error(ctx, 403, ApiErrorCode.FORBIDDEN, e.getMessage()));
        app.exception(MysqlInternalApiPermissionStore.PermissionStoreException.class,
                (e, ctx) -> ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage()));
        app.exception(IllegalArgumentException.class,
                (e, ctx) -> ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage()));
        app.exception(UnsupportedOperationException.class,
                (e, ctx) -> ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage()));
        app.exception(com.harness.trace.store.TraceStoreException.class,
                (e, ctx) -> ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage()));
        app.exception(com.harness.input.memory.MemoryStoreException.class,
                (e, ctx) -> ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage()));
        app.exception(java.io.UncheckedIOException.class,
                (e, ctx) -> ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage()));

        InternalApiPermissionHandler apiPermissionHandler = new InternalApiPermissionHandler(
                apiPermissions, apiPermissionStore, apiRoutes, requestPrincipals);
        apiRoutes.route(app, "GET", "/api/internal-api-endpoints", "internalApiEndpoint.list",
                "permissions", BOOTSTRAP, apiPermissionHandler::endpoints);
        apiRoutes.route(app, "GET", "/api/internal-api-permissions", "internalApiPermission.read",
                "permissions", BOOTSTRAP, apiPermissionHandler::list);
        apiRoutes.route(app, "PUT", "/api/internal-api-permissions", "internalApiPermission.update",
                "permissions", BOOTSTRAP, apiPermissionHandler::replace);

        // Health check
        apiRoutes.route(app, "GET", "/api/health", "health.read", "system", PUBLIC, ctx -> ctx.json(Map.of(
                "status", "ok", "version", VERSION, "authMode", EnvConfig.get().getString(EnvKey.AUTH_MODE, "none"))));
        apiRoutes.route(app, "POST", "/api/auth/logout", "auth.logout", "auth", PUBLIC, ctx -> {
            ctx.removeCookie(RequestPrincipalResolver.MEDIA_COOKIE);
            ctx.json(Map.of("status", "signedOut"));
        });

        ModelConfigurationHandler modelConfigurationHandler = new ModelConfigurationHandler(
                new ModelConfigurationService(
                        new ModelConfigFile(Path.of(
                                EnvConfig.get().getString(
                                        EnvKey.CONFIG_MODEL_FILE,
                                        "./data/model.conf"))),
                        agent));
        apiRoutes.route(app, "GET", "/api/model-config", "modelConfig.read", "model", GLOBAL_MANAGEMENT, modelConfigurationHandler::get);
        apiRoutes.route(app, "PUT", "/api/model-config", "modelConfig.update", "model", GLOBAL_MANAGEMENT, modelConfigurationHandler::update);

        apiRoutes.route(app, "GET", "/api/knowledge-status", "knowledge.status", "knowledge", TENANT, ctx -> ctx.json(agent.knowledgeVectorRuntime().status()));

        // Auth token endpoint
        if ("jwt".equals(authMode)) {
            AuthHandler authHandler = new AuthHandler();
            apiRoutes.route(app, "POST", "/api/auth/token", "auth.login", "auth", PUBLIC, authHandler::handle);
        }

        var knowledgeWikiService = KnowledgeApiRoutes.register(app, apiRoutes, agent,
                knowledgeArtifactRepository, traceStore, Path.of(knowledgeUploadDir));

        // File upload endpoint (for image-to-image and other file references)
        apiRoutes.route(app, "POST", "/api/files/upload", "files.upload", "files", USER, fileUploadHandler::handle);
        apiRoutes.route(app, "GET", "/files/input/{fileName}", "files.read", "files", USER, fileUploadHandler::download);

        KnowledgeWikiHandler wikiHandler = new KnowledgeWikiHandler(
                knowledgeWikiService,
                agent.graphSpaceAccessService(),
                agent.knowledgeGraphStore());
        apiRoutes.route(app, "GET", "/api/wiki", "wiki.list", "knowledge", USER, wikiHandler::list);
        apiRoutes.route(app, "GET", "/api/wiki/export", "wiki.exportAll", "knowledge", GLOBAL_MANAGEMENT, wikiHandler::exportAll);
        apiRoutes.route(app, "GET", "/api/wiki/{conceptId}", "wiki.read", "knowledge", USER, wikiHandler::get);
        apiRoutes.route(app, "PUT", "/api/wiki/{conceptId}", "wiki.update", "knowledge", USER, wikiHandler::update);
        apiRoutes.route(app, "DELETE", "/api/wiki/{conceptId}", "wiki.delete", "knowledge", USER, wikiHandler::delete);
        apiRoutes.route(app, "GET", "/api/wiki/{conceptId}/export", "wiki.export", "knowledge", USER, wikiHandler::export);

        KnowledgeOkfSourceAccess okfSourceAccess = new KnowledgeOkfSourceAccess(
                agent.knowledgeRepository(), knowledgeArtifactRepository,
                agent.sessionStore(), agent.messageStore(), traceStore,
                agent.graphSpaceAccessService(), agent.graphSchemaRegistry());
        KnowledgeOkfHandler okfHandler = new KnowledgeOkfHandler(
                new OkfBundleExporter(
                        agent.knowledgeRepository(), new MysqlKnowledgeIndexOutboxStore(),
                        okfSourceAccess, Clock.systemUTC()),
                new OkfImportService(
                        agent.knowledgeRepository(), okfSourceAccess,
                        Clock.systemUTC()),
                agent.graphSpaceAccessService(),
                EnvConfig.get().getBool(EnvKey.MEMORY_OKF_EXPORT_ENABLED, false));
        apiRoutes.route(app, "POST", "/api/knowledge/okf/export", "okf.export", "knowledge", USER, okfHandler::exportBundle);
        apiRoutes.route(app, "POST", "/api/knowledge/okf/import/review", "okf.review", "knowledge", USER, okfHandler::reviewImport);
        apiRoutes.route(app, "POST", "/api/knowledge/okf/import/commit", "okf.commit", "knowledge", USER, okfHandler::commitImport);

        // Structured knowledge graph endpoints (independent from vector RAG)
        GraphRequestExecutor graphRequestExecutor =
                new GraphRequestExecutor(new GraphRequestAuthenticator());
        GraphDataConverterRegistry graphDataConverterRegistry =
                GraphDataConverterRegistry.withDefaults(mapper);
        graphDataConverterRegistry.register(new LlmGraphDataConverter(
                agent.chatModel(),
                agent.knowledgeGraphStore(),
                agent.graphSchemaRegistry(),
                agent.graphSettings(),
                mapper
        ));
        GraphMutationCommitter graphMutationCommitter = new GraphSpaceBindingRegistrar(
                agent.knowledgeGraphStore()::applyChanges, agent.graphSpaceAccessService());
        GraphCapabilityDescriber graphCapabilityDescriber = new GraphCapabilityDescriber(
                agent::chatModel, agent.embeddingModel().tokenEstimator());
        GraphSchemaWikiCompiler graphSchemaWikiCompiler = new PersistentGraphSchemaWikiCompiler(
                agent.knowledgeRepository(), graphCapabilityDescriber,
                agent.wikiIdentityResolver());
        GraphSpaceWikiCompiler graphSpaceWikiCompiler = new PersistentGraphSpaceWikiCompiler(
                agent.knowledgeRepository(), agent.graphSchemaRegistry());
        GraphDeletionService graphDeletionService = new GraphDeletionService(
                agent.knowledgeGraphStore(),
                agent.graphSpaceAccessService(),
                agent.graphSchemaManagementService(),
                graphSchemaWikiCompiler,
                graphSpaceWikiCompiler,
                agent.knowledgeRepository()
        );
        GraphManagementHandler graphHandler = new GraphManagementHandler(
                agent.knowledgeGraphStore(),
                agent.graphSchemaRegistry(),
                agent.graphSettings(),
                graphRequestExecutor,
                graphMutationCommitter,
                graphDeletionService
        );
        GraphBuildService graphBuildService = new GraphBuildService(
                graphMutationCommitter, graphDataConverterRegistry);
        GraphBuildHandler graphBuildHandler =
                new GraphBuildHandler(graphBuildService, graphRequestExecutor);
        if (agent.graphChangeDraftService() != null) {
            GraphChangeDraftHandler drafts = new GraphChangeDraftHandler(agent.graphChangeDraftService(),
                    new GraphDraftScopeResolver(agent.sessionStore(), mapper));
            apiRoutes.route(app, "GET", "/api/graph/change-drafts/{draftId}", "graph.draft.read", "graph", USER, drafts::read);
            apiRoutes.route(app, "GET", "/api/graph/change-drafts/{draftId}/changes", "graph.draft.changes", "graph", USER, drafts::changes);
            apiRoutes.route(app, "POST", "/api/graph/change-drafts", "graph.draft.prepare", "graph", USER, drafts::prepare);
            apiRoutes.route(app, "POST", "/api/graph/change-drafts/{draftId}/apply", "graph.draft.apply", "graph", USER, drafts::apply);
        }
        GraphSchemaManagementHandler graphSchemaHandler = new GraphSchemaManagementHandler(
                agent.graphSchemaManagementService(),
                agent.graphSettings(),
                graphRequestExecutor,
                graphSchemaWikiCompiler,
                graphDeletionService
        );
        apiRoutes.route(app, "GET", "/api/graph/status", "graph.status", "graph", GLOBAL_MANAGEMENT, graphHandler::status);
        apiRoutes.route(app, "GET", "/api/graph/graphs", "graph.spaces", "graph", GLOBAL_MANAGEMENT, graphHandler::listGraphSpaces);
        apiRoutes.route(app, "DELETE", "/api/graph/graphs", "graph.deleteSpace", "graph", GLOBAL_MANAGEMENT, graphHandler::deleteGraphSpace);
        apiRoutes.route(app, "GET", "/api/graph/schemas", "graph.schemas", "graph", GLOBAL_MANAGEMENT, graphHandler::listSchemas);
        apiRoutes.route(app, "GET", "/api/graph/schemas/{schemaId}", "graph.schema", "graph", GLOBAL_MANAGEMENT, graphHandler::getSchema);
        apiRoutes.route(app, "GET", "/api/graph/schema-configs", "graph.schemaConfigs", "graph", GLOBAL_MANAGEMENT, graphSchemaHandler::list);
        apiRoutes.route(app, "GET", "/api/graph/schema-configs/{schemaId}", "graph.schemaConfig", "graph", GLOBAL_MANAGEMENT, graphSchemaHandler::get);
        apiRoutes.route(app, "POST", "/api/graph/schema-configs", "graph.createSchema", "graph", GLOBAL_MANAGEMENT, graphSchemaHandler::create);
        apiRoutes.route(app, "PUT", "/api/graph/schema-configs/{schemaId}", "graph.updateSchema", "graph", GLOBAL_MANAGEMENT, graphSchemaHandler::update);
        apiRoutes.route(app, "POST", "/api/graph/schema-configs/{schemaId}/enable", "graph.enableSchema", "graph", GLOBAL_MANAGEMENT, graphSchemaHandler::enable);
        apiRoutes.route(app, "POST", "/api/graph/schema-configs/{schemaId}/disable", "graph.disableSchema", "graph", GLOBAL_MANAGEMENT, graphSchemaHandler::disable);
        apiRoutes.route(app, "DELETE", "/api/graph/schema-configs/{schemaId}", "graph.deleteSchema", "graph", GLOBAL_MANAGEMENT, graphSchemaHandler::delete);
        apiRoutes.route(app, "POST", "/api/graph/build/preview", "graph.preview", "graph", GLOBAL_MANAGEMENT, graphBuildHandler::preview);
        apiRoutes.route(app, "POST", "/api/graph/build", "graph.build", "graph", GLOBAL_MANAGEMENT, graphBuildHandler::build);
        apiRoutes.route(app, "POST", "/api/graph/mutations", "graph.mutate", "graph", GLOBAL_MANAGEMENT, graphHandler::mutate);
        apiRoutes.route(app, "POST", "/api/graph/nodes/batch", "graph.upsertNodes", "graph", GLOBAL_MANAGEMENT, graphHandler::upsertNodes);
        apiRoutes.route(app, "GET", "/api/graph/nodes", "graph.listNodes", "graph", GLOBAL_MANAGEMENT, graphHandler::listNodes);
        apiRoutes.route(app, "GET", "/api/graph/nodes/{nodeId}", "graph.readNode", "graph", GLOBAL_MANAGEMENT, graphHandler::getNode);
        apiRoutes.route(app, "DELETE", "/api/graph/nodes/{nodeId}", "graph.deleteNode", "graph", GLOBAL_MANAGEMENT, graphHandler::deleteNode);
        apiRoutes.route(app, "POST", "/api/graph/relations/batch", "graph.upsertRelations", "graph", GLOBAL_MANAGEMENT, graphHandler::upsertRelations);
        apiRoutes.route(app, "GET", "/api/graph/relations", "graph.listRelations", "graph", GLOBAL_MANAGEMENT, graphHandler::listRelations);
        apiRoutes.route(app, "DELETE", "/api/graph/relations/{relationId}", "graph.deleteRelation", "graph", GLOBAL_MANAGEMENT, graphHandler::deleteRelation);
        apiRoutes.route(app, "DELETE", "/api/graph/sources/{sourceId}", "graph.deleteSource", "graph", GLOBAL_MANAGEMENT, graphHandler::deleteSource);
        apiRoutes.route(app, "POST", "/api/graph/query", "graph.query", "graph", GLOBAL_MANAGEMENT, graphHandler::query);

        // Tenant/identity tool permissions: resolved before a run exists, so a disabled tool
        // is never offered to the model. The detached-resume turn has no request to resolve
        // from and re-applies the tenant's DEFAULT profile instead.
        ToolPermissionService toolPermissions = new ToolPermissionService(agent.toolRegistry());
        agent.setToolDenylistResolver((tenantId, identity) ->
                toolPermissions.resolveDisabledTools(tenantId, identity)
                        .orElse(java.util.Set.of()));

        // Chat endpoint (SSE streaming)
        ChatHandler chatHandler = new ChatHandler(agent, activeRequests, requestAuthenticator, toolPermissions);
        apiRoutes.route(app, "POST", "/api/chat", "chat.create", "chat", USER, chatHandler::handle);
        StructuredOutputHandler structuredOutputHandler =
                new StructuredOutputHandler(agent, activeRequests, requestAuthenticator, mapper, toolPermissions);
        apiRoutes.route(app, "POST", "/api/structured-output", "chat.structuredOutput", "chat", USER, structuredOutputHandler::handle);

        RealtimeSessionHandler realtimeSessionHandler =
                new RealtimeSessionHandler(agent, mapper);
        Runtime.getRuntime().addShutdownHook(new Thread(realtimeSessionHandler::close));
        apiRoutes.route(app, "POST", "/api/realtime/sessions", "realtime.create", "realtime", USER, realtimeSessionHandler::create);
        apiRoutes.register(new ApiEndpointDescriptor("realtime.connect", "realtime.connect", "GET",
                "/api/realtime/{sessionId}", "realtime", USER));
        app.wsBeforeUpgrade("/api/realtime/{sessionId}", ctx -> {
            ctx.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE, realtimeSessionHandler.principal(ctx));
            apiRoutes.check(ctx, requestPrincipals, apiPermissions::authorize);
        });
        app.ws("/api/realtime/{sessionId}", ws -> {
            ws.onConnect(realtimeSessionHandler::connect);
            ws.onMessage(realtimeSessionHandler::message);
            ws.onBinaryMessage(realtimeSessionHandler::binary);
            ws.onClose(realtimeSessionHandler::disconnected);
            ws.onError(realtimeSessionHandler::error);
        });

        ToolPermissionHandler toolPermissionHandler = new ToolPermissionHandler(
                toolPermissions, agent.toolRegistry(), requestAuthenticator);
        apiRoutes.route(app, "GET", "/api/tool-permissions", "toolPermission.read", "permissions", TENANT, toolPermissionHandler::get);
        apiRoutes.route(app, "GET", "/api/tool-permissions/identities", "toolPermission.identities", "permissions", TENANT, toolPermissionHandler::identities);
        apiRoutes.route(app, "PUT", "/api/tool-permissions", "toolPermission.update", "permissions", TENANT, toolPermissionHandler::save);

        ConfirmationHandler confirmationHandler =
                new ConfirmationHandler(agent.confirmationManager());
        apiRoutes.route(app, "POST", "/api/confirmations/{requestId}/approve", "confirmation.approve", "chat", USER, confirmationHandler::approve);
        apiRoutes.route(app, "POST", "/api/confirmations/{requestId}/reject", "confirmation.reject", "chat", USER, confirmationHandler::reject);

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
        SessionHandler sessionHandler = new SessionHandler(
                agent.sessionStore(),
                agent.messageStore(),
                agent.messageCache(),
                agent.messageWriteWorker());
        apiRoutes.route(app, "POST", "/api/sessions", "session.create", "sessions", USER, sessionHandler::create);
        apiRoutes.route(app, "GET", "/api/sessions", "session.list", "sessions", USER, sessionHandler::list);
        apiRoutes.route(app, "GET", "/api/sessions/{sessionId}", "session.read", "sessions", SESSION, sessionHandler::detail);
        apiRoutes.route(app, "GET", "/api/sessions/{sessionId}/messages", "session.messages", "sessions", SESSION, sessionHandler::messages);
        SessionTaskHandler sessionTasks = new SessionTaskHandler(agent.subAgentManager(), agent.sessionStore());
        apiRoutes.route(app, "GET", "/api/sessions/{sessionId}/tasks", "session.tasks", "sessions", SESSION, sessionTasks::list);
        apiRoutes.route(app, "GET", "/api/sessions/{sessionId}/stats", "session.stats", "sessions", SESSION, sessionHandler::stats);
        apiRoutes.route(app, "DELETE", "/api/sessions/{sessionId}", "session.delete", "sessions", SESSION, sessionHandler::delete);

        // Bounded process-level cache metrics. Labels never include user or session identifiers.
        apiRoutes.route(app, "GET", "/api/metrics/session-cache", "metrics.sessionCache", "metrics", GLOBAL_MANAGEMENT,
                ctx -> ctx.json(agent.messageCache().metricsSnapshot()));

        // Cancel in-progress chat request
        ChatCancellationHandler cancellation = new ChatCancellationHandler(activeRequests, agent.subAgentManager());
        apiRoutes.route(app, "DELETE", "/api/chat/{sessionId}", "chat.cancel", "chat", SESSION, cancellation::cancel);

        // Get trace by ID
        apiRoutes.route(app, "GET", "/api/trace/{id}", "trace.read", "traces", TRACE, ctx -> {
            String traceId = ctx.pathParam("id");
            AgentTrace authorizedTrace = ctx.attribute(com.harness.server.security.InternalApiResourceAuthorizer.AUTHORIZED_TRACE);
            Optional<AgentTrace> trace = authorizedTrace == null
                    ? traceStore.findById(traceId) : Optional.of(authorizedTrace);
            if (trace.isPresent()) {
                ctx.json(trace.get());
            } else {
                ApiResponses.error(
                        ctx, 404, ApiErrorCode.NOT_FOUND, "Trace not found: " + traceId);
            }
        });

        // List recent traces
        SessionRequestOwnerResolver traceOwners = new SessionRequestOwnerResolver();
        apiRoutes.route(app, "GET", "/api/traces", "trace.list", "traces", USER, ctx -> {
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
            var owner = traceOwners.resolve(ctx, ctx.queryParam("userId"), ctx.queryParam("tenantId"));
            String cursorValue = ctx.queryParam("cursor");
            com.harness.core.model.TraceCursor cursor = null;
            if (cursorValue != null && !cursorValue.isBlank()) {
                int split = cursorValue.indexOf('|');
                if (split < 1) throw new IllegalArgumentException("Invalid trace cursor");
                cursor = new com.harness.core.model.TraceCursor(
                        java.time.Instant.parse(cursorValue.substring(0, split)), cursorValue.substring(split + 1));
            }
            ctx.json(traceStore.findByOwner(owner.userId(), owner.tenantId(), cursor, limit));
        });

        // Trace stats
        int retentionDays = EnvConfig.get().getInt(EnvKey.AUDIT_RETENTION_DAYS, 30);
        apiRoutes.route(app, "GET", "/api/traces/stats", "trace.stats", "traces", USER, ctx -> {
            var owner = traceOwners.resolve(ctx, ctx.queryParam("userId"), ctx.queryParam("tenantId"));
            ctx.json(Map.of("count", traceStore.countByOwner(owner.userId(), owner.tenantId()),
                    "retentionDays", retentionDays));
        });

        TraceFeedbackHandler traceFeedbackHandler = new TraceFeedbackHandler(
                new TraceFeedbackService(traceStore, agent.sessionStore()));
        apiRoutes.route(app, "PUT", "/api/traces/{traceId}/feedback", "trace.feedback", "traces", TRACE, traceFeedbackHandler::update);

        // Manual trace cleanup
        apiRoutes.route(app, "DELETE", "/api/traces/cleanup", "trace.cleanup", "traces", USER, ctx -> {
            var owner = traceOwners.resolve(ctx, ctx.queryParam("userId"), ctx.queryParam("tenantId"));
            int deleted = traceStore.cleanupByOwner(owner.userId(), owner.tenantId(), retentionDays);
            ctx.json(Map.of(
                    "deleted", deleted,
                    "retentionDays", retentionDays));
        });

        // Delete specific trace
        apiRoutes.route(app, "DELETE", "/api/traces/{traceId}", "trace.delete", "traces", TRACE, ctx -> {
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
            apiRoutes.route(app, "GET", "/api/artifacts/{id}", "artifact.download", "artifacts", ARTIFACT, artifactHandler::download);
            apiRoutes.route(app, "GET", "/api/artifacts/{id}/preview", "artifact.preview", "artifacts", ARTIFACT, artifactHandler::preview);
            apiRoutes.route(app, "GET", "/api/artifacts/session/{sessionId}", "artifact.list", "artifacts", SESSION, artifactHandler::listBySession);
        }

        // Project API Discovery endpoints
        ProjectDiscoveryHandler discoveryHandler = new ProjectDiscoveryHandler(mapper, agent);
        apiRoutes.route(app, "POST", "/api/project-discovery/scan", "projectDiscovery.scan", "project", GLOBAL_MANAGEMENT, discoveryHandler::scan);
        apiRoutes.route(app, "POST", "/api/project-discovery/generate", "projectDiscovery.generate", "project", GLOBAL_MANAGEMENT, discoveryHandler::generate);
        apiRoutes.route(app, "GET", "/api/project-discovery/config", "projectDiscovery.read", "project", GLOBAL_MANAGEMENT, discoveryHandler::getConfig);
        apiRoutes.route(app, "PUT", "/api/project-discovery/config", "projectDiscovery.update", "project", GLOBAL_MANAGEMENT, discoveryHandler::updateConfig);
        apiRoutes.route(app, "POST", "/api/project-discovery/reload", "projectDiscovery.reload", "project", GLOBAL_MANAGEMENT, discoveryHandler::reload);

        app.start(host, port);
        log.info("Harness Server started on {}:{} (workers={}, idleTimeout={}s, internalApiAuthorization={})",
                host, port, workers, idleTimeoutMs / 1000,
                EnvConfig.get().getBool(EnvKey.INTERNAL_API_AUTHORIZATION_ENABLED, true));

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
