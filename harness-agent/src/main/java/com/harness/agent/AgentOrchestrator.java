package com.harness.agent;

import com.harness.agent.graph.GraphSpaceAccessService;
import com.harness.agent.graph.GraphSpaceAccessServiceFactory;
import com.harness.agent.memory.AgentMemoryRuntime;
import com.harness.agent.memory.LongTermKnowledgeRetriever;
import com.harness.agent.memory.PreferenceActivationContextBuilder;
import com.harness.agent.knowledge.KnowledgeDiscoveryRouter;
import com.harness.agent.lifecycle.AgentLifecycleHooks;
import com.harness.agent.lifecycle.AutoRoutingHook;
import com.harness.agent.knowledge.KnowledgeReadTool;
import com.harness.agent.knowledge.KnowledgeSearchTool;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
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
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigFile;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.core.modelconfig.ModelConfigInitializer;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.env.RedisConnectionPool;
import com.harness.core.knowledge.KnowledgeHandleCodec;
import com.harness.core.knowledge.LongTermKnowledgeBudgetAllocator;
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
import com.harness.input.document.DocumentSummarizer;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.tool.builtin.FileReadTool;
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
import com.harness.input.gap.GapModelAnalyzer;
import com.harness.input.gap.GapRuleEngine;
import com.harness.input.memory.*;
import com.harness.tool.RunToolCatalog;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}
    }

    private final AgentRuntime runtime;
    private final ModelProviderRuntime modelProviderRuntime;
    private final AgentToolRuntime toolRuntime;
    private final AgentPromptBuilder promptBuilder;
    private final DocumentConversionService documentConversionService;
    private final DocumentSummarizer documentSummarizer;
    private final AgentRunPreparer runPreparer;
    private final AgentRunCoordinator runCoordinator;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final ConfirmationManager confirmationManager;
    private final ToolExecutor toolExecutor;
    private final TraceStore traceStore;
    private final ReplyAuditor replyAuditor;
    private final AgentLifecycleHooks lifecycleHooks;
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
    private LongTermKnowledgeRetriever longTermKnowledgeRetriever;
    private com.harness.tool.knowledge.WikiIdentityResolver wikiIdentityResolver;

    // Skill subsystem
    private final SkillRegistry skillRegistry;

    // Artifact subsystem
    private final ArtifactStore artifactStore;
    private final ArtifactStorageService artifactStorageService;

    public AgentOrchestrator() {
        // Create or migrate model.conf before initializing external infrastructure.
        ModelConfig initialModelConfig = loadModelConfiguration();

        // Database connections (主动建立，按需连接)
        if (MemoryStoreFactory.isMysqlEnabled()
                || "mysql".equalsIgnoreCase(EnvConfig.get().getString(
                EnvKey.AUDIT_STORE, "none"))) {
            MysqlConnectionPool.init();
        }
        if (EnvConfig.get().getString(EnvKey.MEMORY_REDIS_URL) != null) {
            RedisConnectionPool.init();
        }

        ModelProviders initialModelProviders = ModelProviderFactory.createAll(initialModelConfig);
        this.modelProviderRuntime = new ModelProviderRuntime(
                initialModelProviders, initialModelConfig);
        ModelProviders modelProviders = modelProviderRuntime.delegates();
        this.documentConversionService = MarkItDownDocumentConversionService.fromEnvironment();
        this.documentSummarizer = new DocumentSummarizer(
                () -> modelProviderRuntime.current().chat(), UnicodeAwareTextTokenEstimator.INSTANCE);

        this.traceStore = TraceStoreFactory.create();
        this.runtime = new AgentRuntime(
                modelProviders,
                new InputProcessor(
                        new Authenticator(),
                        new MultimodalParser()),
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
                artifactStorageService,
                initialModelConfig);
        this.toolRegistry = toolRuntime.tools();
        this.skillRegistry = toolRuntime.skills();
        this.promptBuilder = new AgentPromptBuilder(skillRegistry, artifactStorageService);
        toolRegistry.register(new FileReadTool(
                artifactStore, documentConversionService, documentSummarizer, null));

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
                artifactStore, sessionInbox, resumeDispatcher,
                runtime.providers().chat());
        // Register sub-agent tools
        toolRegistry.register(new SpawnSubAgentTool(subAgentManager));
        toolRegistry.register(new AwaitSubAgentsTool(subAgentManager));
        toolRegistry.register(new GetSubAgentsTool(subAgentManager));
        toolRegistry.register(new CancelSubAgentsTool(subAgentManager));

        // GapAnalyzer (动态路由)
        GapAnalyzer gapAnalyzer = new GapAnalyzer(
                new GapRuleEngine(), new GapModelAnalyzer(runtime.providers().smallTask()));
        this.lifecycleHooks = new AgentLifecycleHooks(
                List.of(new AutoRoutingHook(gapAnalyzer)), List.of());

        this.memoryRuntime = new AgentMemoryRuntime(
                runtime.providers().chat(), runtime.providers().embedding(),
                skillRegistry, toolRegistry, contextBuilder.vectorStore());
        registerUnifiedKnowledgeTools();
        this.runPreparer = new AgentRunPreparer(
                runtime,
                promptBuilder,
                lifecycleHooks,
                memoryRuntime,
                knowledgeGraphToolEnabled,
                longTermKnowledgeRetriever,
                new PreferenceActivationContextBuilder());
        this.runCoordinator = new AgentRunCoordinator(
                runtime,
                runPreparer,
                memoryRuntime,
                toolRegistry,
                toolExecutor,
                subAgentManager,
                replyAuditor,
                lifecycleHooks);

        log.info("Agent initialized: chat={}, vision={}, voice={}, embedding={}, rerank={}, smallTask={}, tools={}, memory={}",
                runtime.providers().chat().providerName(),
                runtime.providers().vision().providerName(),
                runtime.providers().voice().providerName(),
                runtime.providers().embedding().providerName(),
                runtime.providers().rerank().providerName(),
                runtime.providers().smallTask().providerName(),
                toolRegistry.size(),
                memoryRuntime.enabled() ? "enabled" : "none");
    }

    private void registerUnifiedKnowledgeTools() {
        String ragProvider = EnvConfig.get().getString(EnvKey.RAG_PROVIDER, "milvus");
        if (!memoryRuntime.enabled()) {
            log.info("Knowledge authority and tools disabled (memory=none)");
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        this.longTermKnowledgeRetriever = new LongTermKnowledgeRetriever(
                memoryRuntime.knowledgeRepository(),
                new LongTermKnowledgeBudgetAllocator(
                        runtime.providers().embedding().tokenEstimator(),
                        EnvConfig.get().getDouble(
                                EnvKey.MEMORY_LONGTERM_BUDGET_RATIO,
                                LongTermKnowledgeBudgetAllocator.DEFAULT_BUDGET_RATIO)),
                runtime.providers().embedding().tokenEstimator(),
                objectMapper,
                Clock.systemUTC());
        wikiIdentityResolver = null;
        if (!"none".equalsIgnoreCase(ragProvider)) {
            wikiIdentityResolver = new com.harness.tool.knowledge.WikiIdentityResolver(
                    memoryRuntime.knowledgeRepository(),
                    memoryRuntime.knowledgeProjectionStore(),
                    runtime.providers().embedding(),
                    new com.harness.input.document.DocumentSummarizer(
                            () -> runtime.providers().chat(),
                            runtime.providers().embedding().tokenEstimator()),
                    objectMapper);
        }
        toolRegistry.register(new com.harness.agent.memory.SaveMemoryTool(
                memoryRuntime.knowledgeRepository(), objectMapper, Clock.systemUTC(),
                memoryRuntime::signalKnowledgeIndex,
                com.harness.core.knowledge.PreferenceKeyRegistry.standard(),
                wikiIdentityResolver));
        if ("none".equalsIgnoreCase(ragProvider)) {
            log.info("Knowledge search tools disabled (ragProvider=none); "
                    + "memory capture and MySQL preference injection remain enabled");
            return;
        }
        KnowledgeHandleCodec handleCodec = new KnowledgeHandleCodec(objectMapper);
        KnowledgeDiscoveryRouter router = new KnowledgeDiscoveryRouter(
                memoryRuntime.knowledgeRepository(),
                memoryRuntime.knowledgeProjectionStore(),
                runtime.providers().embedding(),
                toolRuntime.knowledgeAccessService(),
                toolRuntime.graphKnowledgeExecutor(),
                Clock.systemUTC());
        toolRegistry.register(new KnowledgeSearchTool(router, handleCodec, objectMapper));
        toolRegistry.register(new KnowledgeReadTool(
                memoryRuntime.knowledgeRepository(),
                memoryRuntime.knowledgeProjectionStore(),
                handleCodec,
                toolRuntime.knowledgeAccessService(),
                toolRuntime.graphKnowledgeExecutor(),
                objectMapper,
                Clock.systemUTC()));
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
     * @param thinkingLevel null = 未指定（回退 GapAnalysis/模型级默认），否则强制对应思考档位
     */
    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           com.harness.core.model.CancellationToken cancellationToken,
                           ThinkingLevel thinkingLevel) {
        return run(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, thinkingLevel, null);
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           com.harness.core.model.CancellationToken cancellationToken,
                           ThinkingLevel thinkingLevel, String contextUserId) {
        return run(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, thinkingLevel, contextUserId, null);
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           CancellationToken cancellationToken,
                           ThinkingLevel thinkingLevel, String contextUserId, AgentContext agentContext) {
        return modelProviderRuntime.withCurrent(ignored ->
                runCoordinator.run(new AgentRunCommand(
                        token,
                        text,
                        attachments,
                        requestedSessionId,
                        systemPromptOverride,
                        cancellationToken,
                        thinkingLevel,
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
            ThinkingLevel thinkingLevel,
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
                        thinkingLevel,
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
                          StreamCallback callback, ThinkingLevel thinkingLevel) {
        streamRun(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, callback, thinkingLevel, null);
    }

    public void streamRun(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                          String requestedSessionId, String systemPromptOverride,
                          com.harness.core.model.CancellationToken cancellationToken,
                          StreamCallback callback, ThinkingLevel thinkingLevel, String contextUserId) {
        streamRun(token, text, attachments, requestedSessionId, systemPromptOverride, cancellationToken, callback, thinkingLevel, contextUserId, null);
    }

    public void streamRun(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                          String requestedSessionId, String systemPromptOverride,
                          CancellationToken cancellationToken,
                          StreamCallback callback, ThinkingLevel thinkingLevel, String contextUserId,
                          AgentContext agentContext) {
        modelProviderRuntime.withCurrentVoid(ignored ->
                runCoordinator.stream(new AgentRunCommand(
                        token,
                        text,
                        attachments,
                        requestedSessionId,
                        systemPromptOverride,
                        cancellationToken,
                        thinkingLevel,
                        contextUserId,
                        agentContext), callback));
    }

    @Override
    public ModelConfig currentConfiguration() {
        return modelProviderRuntime.currentConfiguration();
    }

    @Override
    public PreparedUpdate prepare(ModelConfig candidateConfiguration) {
        java.util.Objects.requireNonNull(candidateConfiguration, "candidateConfiguration");
        ModelConfig currentConfiguration = modelProviderRuntime.currentConfiguration();
        EmbeddingIdentity currentEmbedding = embeddingIdentity(currentConfiguration);
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
                        currentConfiguration,
                        candidateConfiguration,
                        candidateProviders.voice());
        return () -> modelProviderRuntime.activate(
                candidateProviders, candidateConfiguration, () -> {
            toolRuntime.applyModelTools(candidateTools);
            log.info("Model configuration activated: chat={}, vision={}, voice={}, "
                            + "embedding={}, rerank={}, smallTask={}",
                    candidateProviders.chat().modelName(),
                    candidateProviders.vision().modelName(),
                    candidateProviders.voice().providerName(),
                    candidateProviders.embedding().modelName(),
                    candidateProviders.rerank().modelName(),
                    candidateProviders.smallTask().modelName());
        });
    }

    private static ModelConfig loadModelConfiguration() {
        Path path = Path.of(EnvConfig.get().getString(
                EnvKey.CONFIG_MODEL_FILE, "./data/model.conf"));
        try {
            ModelConfigFile configFile = new ModelConfigFile(path);
            ModelConfigInitializer.InitializationResult initialization =
                    ModelConfigInitializer.initializeIfMissing(
                            configFile, EnvConfig.get().all());
            if (initialization == ModelConfigInitializer.InitializationResult.MIGRATED_LEGACY) {
                log.info("Migrated legacy model settings to {}", path);
            } else if (initialization
                    == ModelConfigInitializer.InitializationResult.CREATED_EMPTY) {
                log.info("Created empty model configuration at {}", path);
            }
            return configFile.read();
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load model configuration from " + path, e);
        }
    }

    private static EmbeddingIdentity embeddingIdentity(ModelConfig config) {
        String provider = config.getString(ModelConfigKey.EMBEDDING_PROVIDER, "")
                .trim().toLowerCase(java.util.Locale.ROOT);
        String defaultBaseUrl = "ollama".equals(provider)
                ? "http://localhost:11434"
                : "https://api.openai.com/v1";
        String defaultModel = "ollama".equals(provider)
                ? "nomic-embed-text"
                : "text-embedding-3-small";
        int defaultDimension = "ollama".equals(provider)
                ? 768
                : ModelConfigKey.EMBEDDING_DIMENSION_DEFAULT;
        return new EmbeddingIdentity(
                provider,
                config.getString(ModelConfigKey.EMBEDDING_BASE_URL, defaultBaseUrl).trim(),
                config.getString(ModelConfigKey.EMBEDDING_MODEL, defaultModel).trim(),
                config.getInt(ModelConfigKey.EMBEDDING_DIMENSION, defaultDimension));
    }

    private record EmbeddingIdentity(
            String provider,
            String baseUrl,
            String model,
            int dimension
    ) {}

    private static void activateToolContext(String userId, String sessionId) {
        LoadSkillTool.setCurrentSession(sessionId);
    }

    private String openRunScope(
            String sessionId,
            CancellationToken cancellationToken,
            RunToolCatalog runToolCatalog,
            RunTrace trace,
            String turnId
    ) {
        String runId = java.util.UUID.randomUUID().toString();
        AgentRunContext runContext = new AgentRunContext(
                runId,
                sessionId,
                cancellationToken,
                trace.traceId(),
                runToolCatalog,
                turnId);
        subAgentManager.openScope(runId);
        SpawnSubAgentTool.setCurrentRunContext(runContext);
        Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
        metadata.put("run_id", runId);
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
        KnowledgeGraphTool.clearCurrentContext();
        KnowledgeAccessService.clearCurrentContext();
        KnowledgeToolRuntimeContext.clear();
        AuthorizedUrlContext.clear();
    }

    /**
     * resume 轮的 URL 授权作用域初始化。独立成包级方法是为了能被测试直接驱动：
     * {@code resumeSession} 自己构造不出来（构造函数需要 DB 与 model.conf）。
     */
    static void initializeUrlScopeForResume(List<MemoryMessage> history) {
        AuthorizedUrlContext.clear();
        AuthorizedUrlContext.set(authorizedUrlsFromUserHistory(history));
    }

    /**
     * 从会话历史重建 resume 轮的 URL 授权作用域。
     *
     * 只认 {@link MemoryMessage#role()} 为 user 的**持久化原始消息**。不能改用
     * {@code UserMessage} 类型判断：resumeSession 会把运行时事件包成
     * {@code UserMessage.from(eventMessage)} 注入进模型历史，而那段文本包含子 Agent 的输出。
     * 从它播种 = 让子 Agent 生成的 URL 自动获得用户级授权，URL 边界直接失效。
     */
    static Set<String> authorizedUrlsFromUserHistory(List<MemoryMessage> messages) {
        if (messages == null) {
            return Set.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        for (MemoryMessage message : messages) {
            if (message != null && "user".equalsIgnoreCase(message.role())) {
                urls.addAll(AuthorizedUrlContext.extractFromUserText(message.text()));
            }
        }
        return Set.copyOf(urls);
    }

    private Set<String> detachedResumeUnavailableTools(AgentContext context) {
        Set<String> unavailable = new HashSet<>();
        unavailable.add(KnowledgeGraphTool.TOOL_NAME);
        unavailable.add(FileReadTool.TOOL_NAME);
        if (Boolean.FALSE.equals(context.needsKnowledgeBase())) {
            unavailable.add(KnowledgeSearchTool.TOOL_NAME);
            unavailable.add(KnowledgeReadTool.TOOL_NAME);
        }
        if (Boolean.FALSE.equals(context.needsWebSearch())) {
            unavailable.add(WebSearchTool.TOOL_NAME);
        }
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
                        toolResult.executionStatus() == ExecutionStatus.CONFIRMATION_REQUIRED);
    }

    /**
     * Load project-apis.json: register endpoint tools + re-register discovery tools with projectRoot.
     * Called at startup and by generate/reload endpoints.
     */
    public void loadProjectApiConfig() {
        toolRuntime.reloadProjectApiConfig();
    }

    // Expose model providers required by direct non-tool integrations.
    public DocumentSummarizer documentSummarizer() { return documentSummarizer; }

    public ChatModelProvider chatModel() { return runtime.providers().chat(); }
    public VisionModelProvider visionModel() { return runtime.providers().vision(); }
    public DocumentConversionService documentConversionService() { return documentConversionService; }
    public EmbeddingModelProvider embeddingModel() { return runtime.providers().embedding(); }
    public RerankModelProvider rerankModel() { return runtime.providers().rerank(); }
    public RealtimeModelProvider realtimeModel() { return runtime.providers().realtime(); }
    public ReActLoopFactory reActLoopFactory() { return runtime.reActLoops(); }

    // Expose memory stores for external use (e.g., cleanup scheduler)
    public SessionStore sessionStore() { return memoryRuntime.sessionStore(); }
    public MessageStore messageStore() { return memoryRuntime.messageStore(); }
    public SessionLifecycleManager sessionLifecycle() { return memoryRuntime.sessionLifecycle(); }
    public SubAgentManager subAgentManager() { return subAgentManager; }
    public SessionMessageCache messageCache() { return memoryRuntime.messageCache(); }
    public MessageWriteWorker messageWriteWorker() { return memoryRuntime.messageWriteWorker(); }

    public com.harness.tool.knowledge.index.KnowledgeReindexService knowledgeReindexService() {
        return memoryRuntime.knowledgeReindexService();
    }
    public SkillRegistry skillRegistry() { return skillRegistry; }
    public TraceStore traceStore() { return traceStore; }
    public ConfirmationManager confirmationManager() { return confirmationManager; }
    public com.harness.tool.rag.VectorStore vectorStore() { return contextBuilder.vectorStore(); }
    public KnowledgeGraphStore knowledgeGraphStore() { return knowledgeGraphStore; }

    public com.harness.tool.knowledge.authority.KnowledgeRepository knowledgeRepository() {
        return memoryRuntime.knowledgeRepository();
    }
    public com.harness.tool.knowledge.WikiIdentityResolver wikiIdentityResolver() {
        return wikiIdentityResolver;
    }
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
        Map<String, List<SessionInbox.SubAgentCompletedEvent>> eventsByTurn = new LinkedHashMap<>();
        for (SessionInbox.SubAgentCompletedEvent event : events) {
            String turnId = event.parentTurnId() != null && !event.parentTurnId().isBlank()
                    ? event.parentTurnId()
                    : event.eventId();
            eventsByTurn.computeIfAbsent(turnId, ignored -> new java.util.ArrayList<>()).add(event);
        }
        eventsByTurn.forEach((turnId, turnEvents) -> resumeTurn(sessionId, turnId, turnEvents));
    }

    private void resumeTurn(
            String sessionId,
            String turnId,
            List<SessionInbox.SubAgentCompletedEvent> events
    ) {
        log.info("[Orchestrator] Resuming session {} with {} events", sessionId, events.size());

        try {
            // Load session history
            if (!memoryRuntime.enabled()) {
                log.warn("[Orchestrator] Cannot resume session: memory not enabled");
                return;
            }

            var session = memoryRuntime.sessionStore()
                    .findByIdForInternalTask(sessionId).orElse(null);
            if (session == null) {
                log.warn("[Orchestrator] Cannot resume session {}: session not found", sessionId);
                return;
            }
            String userId = session.userId();
            String tenantId = session.tenantId();
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
            // The tenant scope is restored from the session row, which was written under the
            // original trusted boundary, so knowledge tools keep their owner/tenant scope
            // instead of silently falling back to a standalone tenant. No request-scoped
            // graph context exists on this path.
            Set<String> unavailableTools = detachedResumeUnavailableTools(resumeAgentContext);
            RunToolCatalog runToolCatalog = createRunToolCatalog(unavailableTools);
            RunTrace trace = runtime.startTrace();
            trace.setSessionId(sessionId);
            trace.recordInput(userId, eventMessage.toString(), List.of());
            trace.recordLlmMeta(runtime.providers().chat().modelName(), "resume");
            String resumeRunId = null;

            try {
                // resume 跑在 session-resume-dispatcher 线程上，不经过 AgentRunPreparer，
                // URL 授权作用域不会自动建立；从会话历史里持久化的 user 消息重建。
                // clear() 保留：dispatcher 是单线程跨会话复用的，播种前先清干净，
                // 否则一旦这里的重建中途抛异常，上一个会话的 URL 集就会残留给下一个会话。
                initializeUrlScopeForResume(shorttermMessages);
                KnowledgeGraphTool.clearCurrentContext();
                KnowledgeAccessService.clearCurrentContext();
                KnowledgeToolRuntimeContext.clear();
                activateToolContext(userId, sessionId);
                KnowledgeAccessService.setCurrentContext(tenantId, null);
                KnowledgeToolRuntimeContext.activate(tenantId, userId, null, null, runToolCatalog);
                resumeRunId = openRunScope(
                        sessionId,
                        cancellationToken,
                        runToolCatalog,
                        trace,
                        turnId);

                // Build system prompt
                GapAnalysis gapAnalysis = lifecycleHooks.beforeLoop(
                        new AgentLifecycleHooks.BeforeLoopContext(
                                eventMessage.toString(), resumeAgentContext))
                        .gapAnalysis();
                String systemPrompt = promptBuilder.buildSystemPrompt(null, sessionId,
                        gapAnalysis.needsKnowledgeBase(), false, null,
                        gapAnalysis.needsWebSearch());

                // Convert messages and add runtime event
                List<ChatMessage> historyChatMessages = memoryRuntime.toChatMessages(shorttermMessages);
                historyChatMessages.add(UserMessage.from(eventMessage.toString()));

                // Execute ReAct loop
                ReActLoop reActLoop = createRequestReActLoop(runToolCatalog);
                ReActResult loopResult = reActLoop.execute(new ReActRequest(
                        systemPrompt,
                        eventMessage.toString(),
                        historyChatMessages,
                        trace,
                        null,
                        cancellationToken,
                        null,
                        null));
                boolean completesTurn = !subAgentManager.hasDetachedTasks(resumeRunId);
                ReActResult result = completesTurn
                        ? lifecycleHooks.beforeFinal(
                                new AgentLifecycleHooks.BeforeFinalContext(sessionId, loopResult))
                                .result()
                        : loopResult;
                result.steps().forEach(trace::addStep);
                recordReactStats(trace, result);
                trace.recordOutput(result.output(), determineRisk(result), true);

                // Save assistant message. Sub-agent events and this turn's own messages go in under
                // one session guard: a run still in flight on the same session must not be able to
                // write between them.
                if (userId != null) {
                    List<MessageBlock> asstBlocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT,
                            result.output() != null ? result.output() : "", null));
                    AgentMemoryRuntime.withSessionWriteLock(sessionId, () -> {
                        memoryRuntime.persistSubAgentEvents(sessionId, userId, turnId, events);
                        memoryRuntime.persistToolMessages(result, sessionId, userId, turnId);
                        memoryRuntime.persistAssistantMessage(
                                sessionId, userId, turnId, asstBlocks, true, completesTurn);
                        memoryRuntime.awaitMessageWrites(turnId);
                    });
                }
                trace.finish();

                log.info("[Orchestrator] Session {} resumed successfully, outputLen={}", sessionId,
                        result.output() != null ? result.output().length() : 0);
            } finally {
                closeRunScope(resumeRunId);
            }

        } catch (Exception e) {
            log.error("[Orchestrator] Failed to resume session {}: {}", sessionId, e.getMessage(), e);
            throw e instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Failed to resume session " + sessionId, e);
        }
    }

    public void shutdown() {
        resumeDispatcher.shutdown();
        subAgentManager.shutdown();
        memoryRuntime.shutdown();
        skillRegistry.evictExpired();
        traceStore.close();
        knowledgeGraphStore.close();
        com.harness.core.env.MysqlConnectionPool.shutdown();
        com.harness.core.env.RedisConnectionPool.shutdown();
        log.info("Agent shut down");
    }
}
