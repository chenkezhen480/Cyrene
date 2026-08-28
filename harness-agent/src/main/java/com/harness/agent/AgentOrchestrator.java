package com.harness.agent;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.agent.graph.GraphSpaceAccessServiceFactory;
import com.harness.agent.memory.AgentMemoryRuntime;
import com.harness.agent.runtime.AgentRuntime;
import com.harness.agent.runtime.AgentRunPreparer;
import com.harness.agent.runtime.AgentRunCoordinator;
import com.harness.agent.runtime.AgentRunCoordinator.AgentRunCommand;
import com.harness.agent.runtime.AgentToolRuntime;
import com.harness.provider.*;
import com.harness.react.*;
import com.harness.trace.ReplyAuditor;
import com.harness.trace.TraceCollectorFactory;
import com.harness.trace.store.TraceStore;
import com.harness.trace.store.TraceStoreFactory;
import com.harness.core.model.*;
import com.harness.core.runtime.RunTrace;
import com.harness.core.runtime.ModelConfigurationRuntime;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.env.RedisConnectionPool;
import com.harness.graph.config.GraphSettings;
import com.harness.graph.config.KnowledgeGraphStoreFactory;
import com.harness.graph.retrieval.AnchoredNeighborhoodGraphRetriever;
import com.harness.graph.retrieval.GraphKnowledgeRetriever;
import com.harness.graph.schema.GraphSchemaManagementService;
import com.harness.graph.schema.GraphSchemaRegistry;
import com.harness.graph.store.KnowledgeGraphStore;
import com.harness.input.InputProcessor;
import com.harness.input.auth.Authenticator;
import com.harness.input.document.DocumentConversionService;
import com.harness.input.document.MarkItDownDocumentConversionService;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.agent.context.ContextBuilder;
import com.harness.agent.context.AgentPromptBuilder;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.tool.artifact.ArtifactStorageService;
import com.harness.tool.artifact.FilesystemArtifactStore;
import com.harness.input.gap.GapAnalysis;
import com.harness.input.gap.GapAnalyzer;
import com.harness.input.gap.GapClassifier;
import com.harness.input.gap.GapRuleEngine;
import com.harness.input.memory.*;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.builtin.UpdateMemoryTool;
import com.harness.tool.builtin.WebSearchTool;
import com.harness.tool.confirmation.ConfirmationManager;
import com.harness.tool.web.AuthorizedUrlContext;
import com.harness.tool.skill.LoadSkillTool;
import com.harness.tool.skill.SkillRegistry;
import com.harness.core.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.harness.core.model.StreamCallback;

/**
 * Wires all layers together using LangChain4j model providers.
 */
public class AgentOrchestrator implements ModelConfigurationRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    static {
        // Register JDBC drivers for fat JAR (SPI discovery may fail)
        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (ClassNotFoundException ignored) {}
        try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException ignored) {}
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}
    }

    private final AgentRuntime runtime;
    private final ModelProviderRuntime modelProviderRuntime;
    private final AgentToolRuntime toolRuntime;
    private final AgentPromptBuilder promptBuilder;
    private final DocumentConversionService documentConversionService;
    private final AgentRunPreparer runPreparer;
    private final AgentRunCoordinator runCoordinator;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final ConfirmationManager confirmationManager;
    private final ToolExecutor toolExecutor;
    private final TraceStore traceStore;
    private final ReplyAuditor replyAuditor;
    private final GapAnalyzer gapAnalyzer;
    private final GraphSettings graphSettings;
    private final GraphSchemaRegistry graphSchemaRegistry;
    private final GraphSchemaManagementService graphSchemaManagementService;
    private final KnowledgeGraphStore knowledgeGraphStore;
    private final GraphKnowledgeRetriever graphKnowledgeRetriever;
    private final GraphSpaceAccessService graphSpaceAccessService;
    private final boolean knowledgeGraphToolEnabled;

    // Sub-agent subsystem
    private final SubAgentManager subAgentManager;
    private final SessionInbox sessionInbox;
    private final SessionResumeDispatcher resumeDispatcher;

    private final AgentMemoryRuntime memoryRuntime;

    // Skill subsystem
    private final SkillRegistry skillRegistry;

    // Artifact subsystem
    private final ArtifactStore artifactStore;
    private final ArtifactStorageService artifactStorageService;

    public AgentOrchestrator() {
        // Database connections (主动建立，按需连接)
        if (MemoryStoreFactory.isEnabled()) {
            MysqlConnectionPool.init();
        }
        if (EnvConfig.get().getString(EnvKey.MEMORY_REDIS_URL) != null) {
            RedisConnectionPool.init();
        }

        ModelProviders initialModelProviders = ModelProviderFactory.createAll();
        this.modelProviderRuntime = new ModelProviderRuntime(initialModelProviders);
        ModelProviders modelProviders = modelProviderRuntime.delegates();
        this.documentConversionService = MarkItDownDocumentConversionService.fromEnvironment();

        this.traceStore = TraceStoreFactory.create();
        this.runtime = new AgentRuntime(
                modelProviders,
                new InputProcessor(
                        new Authenticator(),
                        new MultimodalParser(
                                modelProviders.chat(),
                                documentConversionService)),
                new DefaultReActLoopFactory(modelProviderRuntime),
                new TraceCollectorFactory(traceStore));

        // Independent structured knowledge graph route
        this.graphSettings = GraphSettings.fromEnvironment();
        this.graphSchemaRegistry = GraphSchemaRegistry.fromServiceLoader();
        this.graphSchemaManagementService = GraphSchemaManagementService.open(
                java.nio.file.Path.of(EnvConfig.get().getString(
                        EnvKey.GRAPH_SCHEMA_DIR, "./docker/neo4j/schemas")),
                graphSchemaRegistry
        );
        this.knowledgeGraphStore = KnowledgeGraphStoreFactory.create(graphSchemaRegistry);
        this.knowledgeGraphToolEnabled = !"none".equals(knowledgeGraphStore.providerName());
        this.graphKnowledgeRetriever = new AnchoredNeighborhoodGraphRetriever(
                knowledgeGraphStore, graphSchemaRegistry, graphSettings);
        this.graphSpaceAccessService = GraphSpaceAccessServiceFactory.create(knowledgeGraphStore);

        // Context enrichment and retrieval.
        this.contextBuilder = new ContextBuilder(modelProviders.rerank(), modelProviders.embedding());

        // Artifact subsystem
        String artifactDirStr = EnvConfig.get().getString(EnvKey.ARTIFACT_DIR, "./artifacts");
        Path artifactDirPath = Path.of(artifactDirStr).toAbsolutePath().normalize();
        this.artifactStore = new FilesystemArtifactStore(artifactDirPath);
        int artifactMaxSize = EnvConfig.get().getInt(EnvKey.ARTIFACT_MAX_SIZE_MB, 100);
        this.artifactStorageService = new ArtifactStorageService(
                artifactStore, artifactDirPath, artifactMaxSize);

        // Application-level tool and skill discovery
        this.toolRuntime = new AgentToolRuntime(
                modelProviders,
                graphSettings,
                graphSchemaRegistry,
                knowledgeGraphStore,
                graphKnowledgeRetriever,
                graphSpaceAccessService,
                artifactStore,
                artifactStorageService);
        this.toolRegistry = toolRuntime.tools();
        this.skillRegistry = toolRuntime.skills();
        this.promptBuilder = new AgentPromptBuilder(
                skillRegistry, documentConversionService);

        int confirmationTimeoutSeconds = EnvConfig.get().getInt(
                EnvKey.RISK_CONFIRMATION_TIMEOUT_SECONDS, 300);
        this.confirmationManager = new ConfirmationManager(
                Duration.ofSeconds(confirmationTimeoutSeconds));
        this.toolExecutor = new ToolExecutor(confirmationManager);

        // Reply-level audit before trace persistence.
        this.replyAuditor = new ReplyAuditor();

        // Session inbox and resume dispatcher for sub-agent completion events
        this.sessionInbox = new SessionInbox();
        this.resumeDispatcher = new SessionResumeDispatcher(sessionInbox, this::resumeSession);

        // Sub-agent manager (initialized before ReActEngine so spawn_subagent is available)
        this.subAgentManager = new SubAgentManager(
                runtime.reActLoops(), runtime.traces(), toolExecutor,
                artifactStore, sessionInbox, resumeDispatcher);
        // Register sub-agent tools
        toolRegistry.register(new SpawnSubAgentTool(subAgentManager));
        toolRegistry.register(new AwaitSubAgentsTool(subAgentManager));
        toolRegistry.register(new GetSubAgentsTool(subAgentManager));
        toolRegistry.register(new CancelSubAgentsTool(subAgentManager));

        // GapAnalyzer (动态路由)
        this.gapAnalyzer = new GapAnalyzer(
                new GapRuleEngine(), new GapClassifier(runtime.providers().classifier()));

        this.memoryRuntime = new AgentMemoryRuntime(
                runtime.providers().chat(), skillRegistry, toolRegistry);
        this.runPreparer = new AgentRunPreparer(
                runtime,
                promptBuilder,
                gapAnalyzer,
                memoryRuntime,
                knowledgeGraphToolEnabled);
        this.runCoordinator = new AgentRunCoordinator(
                runtime,
                runPreparer,
                memoryRuntime,
                toolRegistry,
                toolExecutor,
                subAgentManager,
                replyAuditor);

        log.info("Agent initialized: chat={}, vision={}, voice={}, embedding={}, rerank={}, classifier={}, tools={}, memory={}",
                runtime.providers().chat().providerName(),
                runtime.providers().vision().providerName(),
                runtime.providers().voice().providerName(),
                runtime.providers().embedding().providerName(),
                runtime.providers().rerank().providerName(),
                runtime.providers().classifier().providerName(),
                toolRegistry.size(),
                memoryRuntime.enabled() ? "enabled" : "none");
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments) {
        return run(token, text, attachments, null, null, null, null);
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments, String requestedSessionId) {
        return run(token, text, attachments, requestedSessionId, null, null, null);
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           com.harness.core.model.CancellationToken cancellationToken) {
        return run(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, null);
    }

    /**
     * Run agent with full control over session, system prompt override, cancellation, and thinking mode.
     *
     * @param token auth token
     * @param text user input text
     * @param attachments optional attachments
     * @param requestedSessionId optional session ID (null = reuse active)
     * @param systemPromptOverride optional system prompt override (null = use env default)
     * @param cancellationToken optional cancellation token for aborting in-progress runs
     * @param enableThinking null = use env default, true = force thinking, false = force no thinking
     */
    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           com.harness.core.model.CancellationToken cancellationToken,
                           Boolean enableThinking) {
        return run(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, enableThinking, null);
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           com.harness.core.model.CancellationToken cancellationToken,
                           Boolean enableThinking, String contextUserId) {
        return run(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, enableThinking, contextUserId, null);
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           CancellationToken cancellationToken,
                           Boolean enableThinking, String contextUserId, AgentContext agentContext) {
        return modelProviderRuntime.withCurrent(ignored ->
                runCoordinator.run(new AgentRunCommand(
                        token,
                        text,
                        attachments,
                        requestedSessionId,
                        systemPromptOverride,
                        cancellationToken,
                        enableThinking,
                        contextUserId,
                        agentContext)));
    }

    public AgentResult runStructured(
            String token,
            String text,
            List<MultimodalParser.RawAttachment> attachments,
            String requestedSessionId,
            String systemPromptOverride,
            CancellationToken cancellationToken,
            Boolean enableThinking,
            String contextUserId,
            AgentContext agentContext,
            FinalOutputContract.JsonSchema outputContract
    ) {
        return modelProviderRuntime.withCurrent(ignored ->
                runCoordinator.run(new AgentRunCommand(
                        token,
                        text,
                        attachments,
                        requestedSessionId,
                        systemPromptOverride,
                        cancellationToken,
                        enableThinking,
                        contextUserId,
                        agentContext,
                        outputContract)));
    }

    /**
     * Streaming variant of run(). Emits real-time events via callback.
     * All blocking DB operations are made async in streaming mode.
     */
    public void streamRun(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                          String requestedSessionId, String systemPromptOverride,
                          com.harness.core.model.CancellationToken cancellationToken,
                          StreamCallback callback) {
        streamRun(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, callback, null);
    }

    public void streamRun(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                          String requestedSessionId, String systemPromptOverride,
                          com.harness.core.model.CancellationToken cancellationToken,
                          StreamCallback callback, Boolean enableThinking) {
        streamRun(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, callback, enableThinking, null);
    }

    public void streamRun(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                          String requestedSessionId, String systemPromptOverride,
                          com.harness.core.model.CancellationToken cancellationToken,
                          StreamCallback callback, Boolean enableThinking, String contextUserId) {
        streamRun(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, callback, enableThinking, contextUserId, null);
    }

    public void streamRun(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                          String requestedSessionId, String systemPromptOverride,
                          CancellationToken cancellationToken,
                          StreamCallback callback, Boolean enableThinking, String contextUserId,
                          AgentContext agentContext) {
        modelProviderRuntime.withCurrentVoid(ignored ->
                runCoordinator.stream(new AgentRunCommand(
                        token,
                        text,
                        attachments,
                        requestedSessionId,
                        systemPromptOverride,
                        cancellationToken,
                        enableThinking,
                        contextUserId,
                        agentContext), callback));
    }

    @Override
    public PreparedUpdate prepare(EnvConfig candidateConfiguration) {
        java.util.Objects.requireNonNull(candidateConfiguration, "candidateConfiguration");
        EmbeddingIdentity currentEmbedding = embeddingIdentity(EnvConfig.get());
        EmbeddingIdentity candidateEmbedding = embeddingIdentity(candidateConfiguration);
        if (!currentEmbedding.equals(candidateEmbedding)) {
            throw new IllegalArgumentException(
                    "Embedding provider, base URL, model, or dimension cannot be hot-switched; "
                            + "re-index existing knowledge data before changing embedding identity");
        }

        ModelProviders candidateProviders = ModelProviderFactory.createAll(
                candidateConfiguration);
        AgentToolRuntime.PreparedModelTools candidateTools =
                toolRuntime.prepareModelToolChanges(
                        EnvConfig.get(), candidateConfiguration);
        Map<String, String> managedValues = candidateConfiguration.managedModelOverrides();
        return () -> modelProviderRuntime.activate(candidateProviders, () -> {
            EnvConfig.get().replaceManagedModelOverrides(managedValues);
            toolRuntime.applyModelTools(candidateTools);
            log.info("Model configuration activated: chat={}, vision={}, voice={}, "
                            + "embedding={}, rerank={}, classifier={}",
                    candidateProviders.chat().modelName(),
                    candidateProviders.vision().modelName(),
                    candidateProviders.voice().providerName(),
                    candidateProviders.embedding().modelName(),
                    candidateProviders.rerank().modelName(),
                    candidateProviders.classifier().modelName());
        });
    }

    private static EmbeddingIdentity embeddingIdentity(EnvConfig config) {
        String provider = config.getString(EnvKey.MODEL_EMBEDDING_PROVIDER, "")
                .trim().toLowerCase(java.util.Locale.ROOT);
        String defaultBaseUrl = "ollama".equals(provider)
                ? "http://localhost:11434"
                : "https://api.openai.com/v1";
        String defaultModel = "ollama".equals(provider)
                ? "nomic-embed-text"
                : "text-embedding-3-small";
        int defaultDimension = "ollama".equals(provider)
                ? 768
                : EnvKey.MODEL_EMBEDDING_DIM_DEFAULT;
        return new EmbeddingIdentity(
                provider,
                config.getString(EnvKey.MODEL_EMBEDDING_BASE_URL, defaultBaseUrl).trim(),
                config.getString(EnvKey.MODEL_EMBEDDING_MODEL, defaultModel).trim(),
                config.getInt(EnvKey.MODEL_EMBEDDING_DIM, defaultDimension));
    }

    private record EmbeddingIdentity(
            String provider,
            String baseUrl,
            String model,
            int dimension
    ) {}

    private static void activateToolContext(String userId, String sessionId) {
        LoadSkillTool.setCurrentSession(sessionId);
        UpdateMemoryTool.setCurrentUserId(userId);
        UpdateMemoryTool.setCurrentSessionId(sessionId);
    }

    private String openRunScope(
            String sessionId,
            CancellationToken cancellationToken,
            RunToolCatalog runToolCatalog,
            RunTrace trace
    ) {
        String runId = java.util.UUID.randomUUID().toString();
        AgentRunContext runContext = new AgentRunContext(
                runId,
                sessionId,
                cancellationToken,
                trace.traceId(),
                runToolCatalog);
        subAgentManager.openScope(runId);
        SpawnSubAgentTool.setCurrentRunContext(runContext);
        Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
        metadata.put("tool_catalog_version", String.valueOf(runToolCatalog.version()));
        metadata.put("tool_count", String.valueOf(runToolCatalog.size()));
        metadata.put("authorized_tools", runToolCatalog.getAll().stream()
                .map(ToolSpec::name)
                .collect(java.util.stream.Collectors.joining(",")));
        trace.putMetadata(metadata);
        log.debug("[Orchestrator] Sub-agent scope opened: runId={}", runId);
        return runId;
    }

    private static void recordReactStats(
            RunTrace trace,
            ReActResult result
    ) {
        if (result.loopStats() == null) {
            return;
        }
        ReActLoopStats stats = result.loopStats();
        trace.recordReactStats(
                stats.outcome(),
                stats.rounds(),
                stats.toolCalls(),
                stats.reflectionChecks(),
                stats.inputTokens(),
                stats.outputTokens(),
                stats.llmCalls(),
                stats.toolRetries());
    }

    private void closeRunScope(String runId) {
        SpawnSubAgentTool.clearCurrentRunContext();
        if (runId != null) {
            subAgentManager.finishRun(runId);
            log.debug("[Orchestrator] Sub-agent scope finished: runId={}", runId);
        }
        LoadSkillTool.clearCurrentSession();
        UpdateMemoryTool.clearContext();
        KnowledgeGraphTool.clearCurrentContext();
        KnowledgeAccessService.clearCurrentContext();
        AuthorizedUrlContext.clear();
    }

    private Set<String> detachedResumeUnavailableTools(AgentContext context) {
        Set<String> unavailable = new HashSet<>();
        if (Boolean.FALSE.equals(context.needsKnowledgeBase())) {
            unavailable.add(KnowledgeBaseTool.TOOL_NAME);
            unavailable.add(KnowledgeContextReadTool.TOOL_NAME);
        }
        if (Boolean.FALSE.equals(context.needsWebSearch())) {
            unavailable.add(WebSearchTool.TOOL_NAME);
        }
        unavailable.add(KnowledgeGraphTool.TOOL_NAME);
        return Set.copyOf(unavailable);
    }

    private RunToolCatalog createRunToolCatalog(Set<String> unavailableTools) {
        return toolRegistry.snapshot().excluding(unavailableTools);
    }

    private ReActLoop createRequestReActLoop(RunToolCatalog runToolCatalog) {
        return runtime.createLoop(runToolCatalog, toolExecutor);
    }

    /**
     * Extract a user-friendly error message from exception chain.
     * Unwraps ExecutionException/RuntimeException, parses known API error types.
     */
    public static String friendlyErrorMessage(Exception exception) {
        return AgentRunCoordinator.friendlyErrorMessage(exception);
    }

    private RiskLevel determineRisk(ReActResult result) {
        if (requiresConfirmation(result)) {
            return RiskLevel.HIGH;
        }
        boolean hasToolErrors = result.steps().stream()
                .flatMap(s -> s.toolResults().stream())
                .anyMatch(r -> !r.success());
        return hasToolErrors ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    private boolean requiresConfirmation(ReActResult result) {
        return result.steps().stream()
                .flatMap(step -> step.toolResults().stream())
                .anyMatch(toolResult ->
                        toolResult.status() == ToolResult.ResultStatus.CONFIRMATION_REQUIRED);
    }

    /**
     * Load project-apis.json: register endpoint tools + re-register discovery tools with projectRoot.
     * Called at startup and by generate/reload endpoints.
     */
    public void loadProjectApiConfig() {
        toolRuntime.reloadProjectApiConfig();
    }

    // Expose model providers for external use (e.g., direct vision/voice calls)
    public ChatModelProvider chatModel() { return runtime.providers().chat(); }
    public VisionModelProvider visionModel() { return runtime.providers().vision(); }
    public DocumentConversionService documentConversionService() { return documentConversionService; }
    public VoiceModelProvider voiceModel() { return runtime.providers().voice(); }
    public EmbeddingModelProvider embeddingModel() { return runtime.providers().embedding(); }
    public RerankModelProvider rerankModel() { return runtime.providers().rerank(); }
    public RealtimeModelProvider realtimeModel() { return runtime.providers().realtime(); }
    public ReActLoopFactory reActLoopFactory() { return runtime.reActLoops(); }

    // Expose memory stores for external use (e.g., cleanup scheduler)
    public SessionStore sessionStore() { return memoryRuntime.sessionStore(); }
    public MessageStore messageStore() { return memoryRuntime.messageStore(); }
    public PreferenceStore preferenceStore() { return memoryRuntime.preferenceStore(); }
    public SessionLifecycleManager sessionLifecycle() { return memoryRuntime.sessionLifecycle(); }
    public PreferenceRefinementWorker refinementWorker() { return memoryRuntime.refinementWorker(); }
    public SubAgentManager subAgentManager() { return subAgentManager; }
    public SessionMessageCache messageCache() { return memoryRuntime.messageCache(); }
    public MessageWriteWorker messageWriteWorker() { return memoryRuntime.messageWriteWorker(); }
    public SkillRegistry skillRegistry() { return skillRegistry; }
    public TraceStore traceStore() { return traceStore; }
    public ConfirmationManager confirmationManager() { return confirmationManager; }
    public com.harness.tool.rag.VectorStore vectorStore() { return contextBuilder.vectorStore(); }
    public KnowledgeGraphStore knowledgeGraphStore() { return knowledgeGraphStore; }
    public GraphSpaceAccessService graphSpaceAccessService() { return graphSpaceAccessService; }
    public GraphSchemaRegistry graphSchemaRegistry() { return graphSchemaRegistry; }
    public GraphSchemaManagementService graphSchemaManagementService() { return graphSchemaManagementService; }
    public GraphSettings graphSettings() { return graphSettings; }

    // Expose artifact subsystem
    public ArtifactStore artifactStore() { return artifactStore; }
    public ArtifactStorageService artifactStorageService() { return artifactStorageService; }


    /**
     * Convert MemoryMessage list to LangChain4j ChatMessage list for ReAct history injection.
     * Summary rows are converted to AiMessage to preserve compressed context.
     */
    private void resumeSession(String sessionId, List<SessionInbox.SubAgentCompletedEvent> events) {
        log.info("[Orchestrator] Resuming session {} with {} events", sessionId, events.size());

        try {
            // Load session history
            if (!memoryRuntime.enabled()) {
                log.warn("[Orchestrator] Cannot resume session: memory not enabled");
                return;
            }

            String userId = memoryRuntime.findSessionUserId(sessionId);
            List<Preference> longtermPrefs = memoryRuntime.loadPreferences(userId);
            List<MemoryMessage> shorttermMessages =
                    memoryRuntime.loadMessages(sessionId, userId);

            // Build runtime event message
            StringBuilder eventMessage = new StringBuilder();
            eventMessage.append("[Runtime Event]\n\n");
            eventMessage.append("此前启动的子任务已经完成。\n\n");

            for (SessionInbox.SubAgentCompletedEvent event : events) {
                eventMessage.append("Task ID: ").append(event.taskId()).append("\n");
                eventMessage.append("Original task: ").append(event.taskDescription()).append("\n");
                eventMessage.append("Status: ").append(event.result().status()).append("\n");

                if (event.result().output() != null) {
                    eventMessage.append("Result: ").append(event.result().output()).append("\n");
                }
                if (event.result().error() != null) {
                    eventMessage.append("Error: ").append(event.result().error()).append("\n");
                }
                if (!event.result().contractValidation().violations().isEmpty()) {
                    eventMessage.append("Contract violations: ")
                            .append(String.join("; ",
                                    event.result().contractValidation().violations()))
                            .append("\n");
                }
                eventMessage.append("\n");
            }

            eventMessage.append("请结合当前会话历史和该结果，继续处理用户的请求。");

            CancellationToken cancellationToken = new CancellationToken();
            AgentContext resumeAgentContext = AgentContext.empty();
            // Detached resume events currently persist the user/session identity but not the
            // trusted caller's tenantId. Keep graph retrieval unavailable here instead of
            // silently falling back to the standalone tenant and crossing graph-space scopes.
            Set<String> unavailableTools = detachedResumeUnavailableTools(resumeAgentContext);
            RunToolCatalog runToolCatalog = createRunToolCatalog(unavailableTools);
            RunTrace trace = runtime.startTrace();
            trace.setSessionId(sessionId);
            trace.recordInput(userId, eventMessage.toString(), List.of());
            trace.recordLlmMeta(runtime.providers().chat().modelName(), "resume");
            String resumeRunId = null;

            try {
                AuthorizedUrlContext.clear();
                KnowledgeGraphTool.clearCurrentContext();
                KnowledgeAccessService.clearCurrentContext();
                activateToolContext(userId, sessionId);
                resumeRunId = openRunScope(
                        sessionId,
                        cancellationToken,
                        runToolCatalog,
                        trace);

                // Build system prompt
                GapAnalysis gapAnalysis = gapAnalyzer.analyze(eventMessage.toString(), resumeAgentContext);
                String systemPrompt = promptBuilder.buildSystemPrompt(longtermPrefs, null, sessionId,
                        gapAnalysis.needsKnowledgeBase(), false, null,
                        gapAnalysis.needsWebSearch());

                // Convert messages and add runtime event
                List<ChatMessage> historyChatMessages = memoryRuntime.toChatMessages(shorttermMessages);
                historyChatMessages.add(UserMessage.from(eventMessage.toString()));

                // Execute ReAct loop
                ReActLoop reActLoop = createRequestReActLoop(runToolCatalog);
                ReActResult result = reActLoop.execute(new ReActRequest(
                        systemPrompt,
                        eventMessage.toString(),
                        historyChatMessages,
                        trace,
                        null,
                        cancellationToken,
                        null,
                        null));
                result.steps().forEach(trace::addStep);
                recordReactStats(trace, result);
                trace.recordOutput(result.output(), determineRisk(result), true);
                trace.finish();

                // Save assistant message
                if (userId != null) {
                    List<MessageBlock> asstBlocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT,
                            result.output() != null ? result.output() : "", null));
                    memoryRuntime.persistAssistantMessage(
                            sessionId, userId, asstBlocks, true);
                }

                log.info("[Orchestrator] Session {} resumed successfully, outputLen={}", sessionId,
                        result.output() != null ? result.output().length() : 0);
            } finally {
                closeRunScope(resumeRunId);
            }

        } catch (Exception e) {
            log.error("[Orchestrator] Failed to resume session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    public void shutdown() {
        resumeDispatcher.shutdown();
        subAgentManager.shutdown();
        memoryRuntime.shutdown();
        skillRegistry.evictExpired();
        traceStore.close();
        knowledgeGraphStore.close();
        com.harness.core.env.PgConnectionPool.shutdown();
        com.harness.core.env.MysqlConnectionPool.shutdown();
        com.harness.core.env.RedisConnectionPool.shutdown();
        log.info("Agent shut down");
    }
}
