package com.harness.agent;

import com.harness.ai.model.*;
import com.harness.ai.react.ReActEngine;
import com.harness.audit.ReplyAuditor;
import com.harness.audit.TraceCollector;
import com.harness.audit.store.TraceStore;
import com.harness.audit.store.TraceStoreFactory;
import com.harness.core.model.*;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.env.MysqlConnectionPool;
import com.harness.env.RedisConnectionPool;
import com.harness.input.InputProcessor;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.input.multimodal.TextChunker;
import com.harness.preprocess.ContextBuilder;
import com.harness.core.model.Artifact;
import com.harness.core.model.ArtifactStore;
import com.harness.preprocess.artifact.ArtifactStorageService;
import com.harness.preprocess.artifact.FilesystemArtifactStore;
import com.harness.preprocess.gap.GapAnalysis;
import com.harness.preprocess.gap.GapAnalyzer;
import com.harness.preprocess.gap.GapClassifier;
import com.harness.preprocess.gap.GapRuleEngine;
import com.harness.preprocess.memory.*;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.builtin.FfmpegTool;
import com.harness.tool.builtin.ImageGenerationTool;
import com.harness.tool.builtin.PythonSandboxTool;
import com.harness.tool.builtin.UpdateMemoryTool;
import com.harness.tool.builtin.VideoGenerationTool;
import com.harness.tool.builtin.WebSearchTool;
import com.harness.tool.discovery.CodeGlobTool;
import com.harness.tool.discovery.CodeGrepTool;
import com.harness.tool.discovery.ReadClassHierarchyTool;
import com.harness.tool.mcp.McpServerConfig;
import com.harness.tool.mcp.McpToolDiscovery;
import com.harness.tool.skill.LoadSkillTool;
import com.harness.tool.skill.SkillLoader;
import com.harness.tool.skill.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import com.harness.core.model.StreamCallback;
import com.harness.core.model.StreamEvent;
import com.harness.ai.react.ReActListener;

/**
 * Wires all layers together using LangChain4j model providers.
 */
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    static {
        // Register JDBC drivers for fat JAR (SPI discovery may fail)
        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (ClassNotFoundException ignored) {}
        try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException ignored) {}
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}
    }

    // 7 model providers
    private final ChatModelProvider chatModelProvider;
    private final VisionModelProvider visionModelProvider;
    private final VoiceModelProvider voiceModelProvider;
    private final EmbeddingModelProvider embeddingModelProvider;
    private final RerankModelProvider rerankModelProvider;
    private final RealtimeModelProvider realtimeModelProvider;
    private final ClassifierModelProvider classifierModelProvider;

    private final InputProcessor inputProcessor;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ReActEngine reactEngine;
    private final TraceStore traceStore;
    private final ReplyAuditor replyAuditor;
    private final GapAnalyzer gapAnalyzer;

    // Sub-agent subsystem
    private final SubAgentOrchestrator subAgentOrchestrator;

    // Memory subsystem
    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final PreferenceStore preferenceStore;
    private final SessionLifecycleManager sessionLifecycle;
    private final PreferenceRefinementWorker refinementWorker;
    private final MemoryCompressor memoryCompressor;
    private final SessionCleanupScheduler cleanupScheduler;
    private final SessionMessageCache messageCache;
    private final MessageWriteWorker messageWriteWorker;

    // Skill subsystem
    private final SkillRegistry skillRegistry;

    // Artifact subsystem
    private final ArtifactStore artifactStore;
    private final ArtifactStorageService artifactStorageService;

    private String activeSessionId;  // CLI mode: reuse across calls

    public AgentOrchestrator() {
        // Database connections (主动建立，按需连接)
        if (MemoryStoreFactory.isEnabled()) {
            MysqlConnectionPool.init();
        }
        if (EnvConfig.get().getString(EnvKey.MEMORY_REDIS_URL) != null) {
            RedisConnectionPool.init();
        }

        // Create all model providers
        this.chatModelProvider = ModelProviderFactory.createChat();
        this.visionModelProvider = ModelProviderFactory.createVision();
        this.voiceModelProvider = ModelProviderFactory.createVoice();
        this.embeddingModelProvider = ModelProviderFactory.createEmbedding();
        this.rerankModelProvider = ModelProviderFactory.createRerank();
        this.realtimeModelProvider = ModelProviderFactory.createRealtime();
        this.classifierModelProvider = ModelProviderFactory.createClassifier();

        // Layer 1: Input
        this.inputProcessor = new InputProcessor(chatModelProvider, visionModelProvider, voiceModelProvider);

        // Layer 2: Preprocess
        this.contextBuilder = new ContextBuilder(rerankModelProvider, embeddingModelProvider, chatModelProvider);

        // Layer 3: Tools
        this.toolRegistry = new ToolRegistry();
        registerBuiltinTools();
        registerMcpTools();
        this.toolExecutor = new ToolExecutor(toolRegistry);

        // Skill subsystem (load index, register load_skill tool)
        this.skillRegistry = new SkillRegistry();
        initSkills();

        // Load project API discovery config (HttpApiTools for confirmed endpoints)
        loadProjectApiConfig();

        // Sub-agent orchestrator (initialized before ReActEngine so spawn_subagent is available)
        this.subAgentOrchestrator = new SubAgentOrchestrator(
                chatModelProvider, visionModelProvider, voiceModelProvider, toolRegistry, toolExecutor);
        // Register spawn_subagent tool
        toolRegistry.register(new SpawnSubAgentTool(subAgentOrchestrator));

        // ReAct Engine (LangChain4j ChatLanguageModel + tools + multimodal fallback)
        this.reactEngine = new ReActEngine(chatModelProvider, toolRegistry, toolExecutor,
                visionModelProvider, voiceModelProvider);

        // Layer 5: Audit
        this.traceStore = TraceStoreFactory.create();
        this.replyAuditor = new ReplyAuditor();

        // GapAnalyzer (动态路由)
        this.gapAnalyzer = new GapAnalyzer(new GapRuleEngine(), new GapClassifier(classifierModelProvider));

        // Artifact subsystem
        String artifactDirStr = EnvConfig.get().getString(EnvKey.ARTIFACT_DIR, "./artifacts");
        Path artifactDirPath = Path.of(artifactDirStr).toAbsolutePath().normalize();
        this.artifactStore = new FilesystemArtifactStore(artifactDirPath);
        int artifactMaxSize = EnvConfig.get().getInt(EnvKey.ARTIFACT_MAX_SIZE_MB, 100);
        this.artifactStorageService = new ArtifactStorageService(artifactStore, artifactDirPath, artifactMaxSize);

        // Memory subsystem — skip entirely when disabled
        if (MemoryStoreFactory.isEnabled()) {
            this.sessionStore = MemoryStoreFactory.createSessionStore();
            this.messageStore = MemoryStoreFactory.createMessageStore();
            this.preferenceStore = MemoryStoreFactory.createPreferenceStore();
            this.sessionLifecycle = new SessionLifecycleManager(sessionStore, messageStore);
            this.refinementWorker = new PreferenceRefinementWorker(messageStore, preferenceStore, chatModelProvider);
            this.memoryCompressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);
            this.messageCache = MemoryStoreFactory.createMessageCache();
            this.messageCache.setOnEvict(skillRegistry::clearSession);
            this.messageWriteWorker = new MessageWriteWorker(messageStore);
            refinementWorker.start();
            messageWriteWorker.start();
            cleanupScheduler = new SessionCleanupScheduler(sessionStore, sessionLifecycle, refinementWorker, messageCache, skillRegistry);
            cleanupScheduler.start();
            // Register update_memory tool — LLM主动追加长期记忆
            toolRegistry.register(new UpdateMemoryTool((userId, content, sessionId) -> {
                // 追加模式：加载已有记忆，用 # 拼接新内容
                String existing = preferenceStore.loadByUser(userId).stream()
                        .filter(p -> "memory".equals(p.category()))
                        .findFirst()
                        .map(Preference::content)
                        .orElse("");
                String merged = existing.isEmpty() ? content : existing + " # " + content;
                preferenceStore.upsert(userId, "memory", merged, sessionId);
            }));
        } else {
            this.sessionStore = null;
            this.messageStore = null;
            this.preferenceStore = null;
            this.sessionLifecycle = null;
            this.refinementWorker = null;
            this.memoryCompressor = null;
            this.messageCache = new InMemorySessionMessageCache();
            this.messageCache.setOnEvict(skillRegistry::clearSession);
            this.messageWriteWorker = null;
            this.cleanupScheduler = null;
        }

        log.info("Agent initialized: chat={}, vision={}, voice={}, embedding={}, rerank={}, classifier={}, tools={}, memory={}",
                chatModelProvider.providerName(),
                visionModelProvider.providerName(),
                voiceModelProvider.providerName(),
                embeddingModelProvider.providerName(),
                rerankModelProvider.providerName(),
                classifierModelProvider.providerName(),
                toolRegistry.size(),
                MemoryStoreFactory.isEnabled() ? "enabled" : "none");
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
                           com.harness.core.model.CancellationToken cancellationToken,
                           Boolean enableThinking, String contextUserId, AgentContext agentContext) {
        long runStart = System.currentTimeMillis();
        int attachCount = attachments != null ? attachments.size() : 0;
        log.debug("[Orchestrator] Run start: textLen={}, sessionId={}, attachments={}",
                text != null ? text.length() : 0, requestedSessionId, attachCount);

        TraceCollector trace = new TraceCollector(traceStore);
        String sessionId = null;

        try {
            // Layer 1: Input
            InputProcessor.InputResult input = inputProcessor.process(token, text, attachments, contextUserId);
            trace.recordInput(input.userId(), text,
                    input.message().attachments().stream().map(AgentMessage.Attachment::name).toList());

            // Build enhanced text: user text + extracted file contents
            String enhancedText = text;
            if (!input.parsedContents().isEmpty()) {
                StringBuilder sb = new StringBuilder(text);
                for (ParsedContent pc : input.parsedContents()) {
                    if (pc == null) continue; // null placeholder for non-text attachments (images, etc.)
                    sb.append("\n\n[File: ").append(pc.metadata().get("file_name")).append("]\n");
                    sb.append(pc.text());
                }
                enhancedText = sb.toString();
            }

            // Inject File content from context: resolve path, read disk, extract text for documents
            if (agentContext != null && agentContext.data() != null) {
                Object fileObj = agentContext.data().get("File");
                if (fileObj != null) {
                    List<String> filePaths = new ArrayList<>();
                    if (fileObj instanceof String s) filePaths.add(s);
                    else if (fileObj instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof String url) filePaths.add(url);
                        }
                    }

                    StringBuilder sb = new StringBuilder(enhancedText);
                    boolean hasContent = false;
                    for (String filePath : filePaths) {
                        String extracted = extractContextFileContent(filePath);
                        if (extracted != null) {
                            if (!hasContent) {
                                sb.append("\n\n[参考文件 / Reference Files]");
                                hasContent = true;
                            }
                            String name = filePath.substring(filePath.lastIndexOf('/') + 1);
                            sb.append("\n\n[File: ").append(name).append("]\n");
                            sb.append(extracted);
                            log.debug("[Orchestrator] Extracted content from context.File: {}", filePath);
                        }
                    }
                    enhancedText = sb.toString();
                }
            }

            final String ragInput = enhancedText;

            // GapAnalyzer: 动态路由判定
            AgentContext actx = agentContext != null ? agentContext : AgentContext.empty();
            GapAnalysis gapAnalysis = gapAnalyzer.analyze(ragInput, actx);
            trace.builder().metadata(gapMetadata(gapAnalysis, trace.builder().build().metadata()));

            // ===== Layer 1.5: Session lifecycle =====
            List<MemoryMessage> shorttermMessages = List.of();
            List<Preference> longtermPrefs = List.of();

            // RAG retrieval; GapAnalysis controls whether retrieval happens
            final GapAnalysis finalGap = gapAnalysis;
            CompletableFuture<ContextBuilder.ContextResult> ragFuture = CompletableFuture.supplyAsync(() ->
                    contextBuilder.build(ragInput, finalGap));

            final String userId = input.userId();
            if (MemoryStoreFactory.isEnabled() && userId != null) {
                SessionLifecycleManager.LifecycleResult lifecycle = sessionLifecycle.process(input.userId(), requestedSessionId);
                sessionId = lifecycle.session().id();
                activeSessionId = sessionId;
                final String sid = sessionId;
                log.debug("[Memory] Session resolved: id={}, isNew={}, timedOut={}",
                        sessionId, lifecycle.isNewSession(), lifecycle.timedOutSessionIds().size());
                trace.builder().sessionId(sessionId);
                Map<String, String> meta = new HashMap<>(trace.builder().build().metadata());
                meta.put("session_id", sessionId);
                meta.put("session_new", String.valueOf(lifecycle.isNewSession()));
                if (!lifecycle.timedOutSessionIds().isEmpty()) {
                    meta.put("sessions_timed_out", String.join(",", lifecycle.timedOutSessionIds()));
                }
                trace.builder().metadata(meta);

                // Set session title from user's first message
                if (lifecycle.isNewSession()) {
                    String title = text.length() > 100 ? text.substring(0, 100) : text;
                    sessionStore.updateTitle(sessionId, title);
                }

                // Async: check refinement worthiness for timed-out sessions
                for (String timedOutId : lifecycle.timedOutSessionIds()) {
                    final String tid = timedOutId;
                    CompletableFuture.runAsync(() -> {
                        if (sessionLifecycle.isWorthyOfRefinement(tid)) {
                            refinementWorker.submit(tid, userId);
                        }
                    });
                }

                // Load short-term memory and long-term prefs in parallel with RAG
                CompletableFuture<List<MemoryMessage>> shorttermFuture = CompletableFuture.supplyAsync(() -> {
                    List<MemoryMessage> cached = messageCache.getIfPresent(sid);
                    if (cached != null) {
                        log.debug("Cache hit for session: {}", sid);
                        return cached;
                    }
                    List<MemoryMessage> loaded = messageStore.loadForContext(sid);
                    messageCache.put(sid, userId, loaded);
                    return loaded;
                });
                CompletableFuture<List<Preference>> longtermFuture = CompletableFuture.supplyAsync(() -> {
                    List<Preference> prefs = preferenceStore.loadByUser(userId);
                    log.debug("[Memory] Loaded {} long-term preferences for user {}", prefs.size(), userId);
                    return prefs;
                });

                CompletableFuture.allOf(shorttermFuture, longtermFuture, ragFuture).join();
                shorttermMessages = shorttermFuture.join();
                longtermPrefs = longtermFuture.join();
            } else {
                ragFuture.join();
                // Memory disabled — use requested/active sessionId for skill isolation
                sessionId = requestedSessionId != null ? requestedSessionId : java.util.UUID.randomUUID().toString();
            }

            // Set ThreadLocal for skill tools session-scoped lookup
            final String finalSessionId = sessionId;
            LoadSkillTool.setCurrentSession(finalSessionId);
            UpdateMemoryTool.setCurrentUserId(userId);
            UpdateMemoryTool.setCurrentSessionId(finalSessionId);

            // ===== Layer 2: Preprocess =====
            ContextBuilder.ContextResult ctx = ragFuture.join();
            trace.recordPreprocess(null, ctx.ragHitIds(), null);
            String systemPrompt = buildSystemPrompt(longtermPrefs, systemPromptOverride, sessionId, Boolean.TRUE.equals(gapAnalysis.needsWebSearch()));
            log.debug("[Orchestrator] System prompt: {} chars, longterm={}, needsWebSearch={}",
                    systemPrompt.length(), !longtermPrefs.isEmpty(), gapAnalysis.needsWebSearch());

            // Inject RAG context into user message (not system prompt) to preserve prompt cache
            String finalUserMessage = enhancedText;
            if (ctx.hasContext()) {
                finalUserMessage = enhancedText + "\n\n" + ctx.contextBlock();
            }
            trace.recordLlmMeta(chatModelProvider.modelName(), "v1");

            // 压缩检查
            if (sessionId != null && userId != null) {
                var outcome = applyCompression(sessionId, userId, shorttermMessages, finalUserMessage, systemPrompt);
                shorttermMessages = outcome.finalMessages();

                if (outcome.hasMajor()) {
                    Map<String, String> meta = new HashMap<>(trace.builder().build().metadata());
                    meta.put("compression_type", outcome.majorResult().type().name());
                    meta.put("messages_before", String.valueOf(outcome.majorResult().messagesBefore()));
                    meta.put("messages_after", String.valueOf(outcome.majorResult().messagesAfter()));
                    trace.builder().metadata(meta);
                }
                // 用户消息：异步写DB + 同步更新缓存
                List<MessageBlock> userBlocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT, enhancedText, null));
                messageWriteWorker.submit(sessionId, "user", userBlocks, false);
                messageCache.append(sessionId, userId, new MemoryMessage(0, sessionId, "user", userBlocks, false, null));
                sessionStore.updateLastActive(sessionId);
            }

            // ===== Layer 3+4: ReAct loop（AI决策 + 工具执行 + 小压缩去除工具块） =====
            // Set parent cancellation token for sub-agents
            subAgentOrchestrator.setParentToken(cancellationToken);
            List<ChatMessage> historyChatMessages = convertToChatMessages(shorttermMessages);

            // Unified block accumulation via listener (same pattern as streamRun)
            List<MessageBlock> blockAccumulator = new ArrayList<>();
            StringBuilder textAccumulator = new StringBuilder();

            ReActListener blockListener = new ReActListener() {
                @Override public void onToken(String token) {}
                @Override public void onToolCallStart(String toolName, String arguments) {}
                @Override
                public void onStep(ReActStep step) {
                    // Tool iteration: accumulate reasoning text (LLM's pre-tool-call thinking)
                    // Final answer: accumulate answer text (post-tools response)
                    if (step.toolCalls() != null && !step.toolCalls().isEmpty()) {
                        // Tool iteration — reasoning text only, skip observation (tool output)
                        if (step.thought() != null && !step.thought().isBlank()) {
                            textAccumulator.append(step.thought());
                        }
                    } else {
                        // Final answer — store raw text; system prompt prevents artifact links
                        if (step.observation() != null && !step.observation().isBlank()) {
                            textAccumulator.append(step.observation());
                        }
                    }
                }
                @Override
                public void onArtifact(java.util.List<Artifact> artifacts) {
                    // Flush accumulated text as TEXT block before inserting artifact
                    if (textAccumulator.length() > 0) {
                        blockAccumulator.add(new MessageBlock(MessageBlock.BlockType.TEXT, textAccumulator.toString(), null));
                        textAccumulator.setLength(0);
                    }
                    for (Artifact a : artifacts) {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("type", a.type().name());
                        if (a.mimeType() != null) meta.put("mimeType", a.mimeType());
                        if (a.name() != null) meta.put("name", a.name());
                        meta.put("previewUrl", a.previewUrl());
                        meta.put("downloadUrl", a.downloadUrl());
                        blockAccumulator.add(new MessageBlock(MessageBlock.BlockType.ARTIFACT, null, a.id(), meta));
                    }
                }
            };

            // thinking 优先级：显式 enableThinking > GapAnalysis.needsThinking > 环境变量
            Boolean effectiveThinking = enableThinking != null ? enableThinking : gapAnalysis.needsThinking();
            ReActEngine.ReActResult result = reactEngine.execute(systemPrompt, finalUserMessage, historyChatMessages, trace.builder(), blockListener, cancellationToken, effectiveThinking);
            result.steps().forEach(trace::addStep);

            // Record ReAct loop quality signals into trace metadata
            if (result.loopStats() != null) {
                ReActEngine.ReActLoopStats stats = result.loopStats();
                trace.recordReactStats(stats.outcome(), stats.rounds(), stats.toolCalls(),
                        stats.reflectionChecks());
            }

            // Flush remaining text after loop
            if (textAccumulator.length() > 0) {
                blockAccumulator.add(new MessageBlock(MessageBlock.BlockType.TEXT, textAccumulator.toString(), null));
            }

            // Accumulated blocks are authoritative — onStep/onArtifact always fire before execute() returns
            List<MessageBlock> asstBlocks = blockAccumulator.isEmpty()
                    ? List.of(new MessageBlock(MessageBlock.BlockType.TEXT, result.output() != null ? result.output() : "", null))
                    : blockAccumulator;

            // Tool 消息进缓存（不落 DB），供下一轮 preprocess 小压缩
            cacheToolMessages(result, sessionId, userId);

            RiskLevel risk = determineRisk(result);
            trace.recordOutput(result.output(), risk, true);

            // ===== Reply audit (async, non-blocking) =====
            final String replyText = result.output();
            CompletableFuture.runAsync(() -> {
                try {
                    ReplyAuditor.ReplyAuditResult auditResult = replyAuditor.audit(replyText);
                    if (!auditResult.passed()) {
                        log.warn("[ReplyAuditor] Audit failed: score={}, reason={}", auditResult.score(), auditResult.reason());
                    } else {
                        log.debug("[ReplyAuditor] Audit passed: score={}", auditResult.score());
                    }
                    // Store in trace metadata (best-effort, trace may already be finishing)
                    Map<String, String> auditMeta = new HashMap<>(trace.builder().build().metadata());
                    auditMeta.put("reply_audit_passed", String.valueOf(auditResult.passed()));
                    auditMeta.put("reply_audit_score", String.valueOf(auditResult.score()));
                    auditMeta.put("reply_audit_reason", auditResult.reason());
                    trace.builder().metadata(auditMeta);
                } catch (Exception e) {
                    log.debug("[ReplyAuditor] Async audit failed: {}", e.getMessage());
                }
            });

            // Save AI message (async via worker)
            if (sessionId != null && userId != null) {
                messageWriteWorker.submit(sessionId, "assistant", asstBlocks, false);
                messageCache.append(sessionId, userId, new MemoryMessage(0, sessionId, "assistant", asstBlocks, false, null));
                sessionStore.updateLastActive(sessionId);
            }

            AgentTrace agentTrace = trace.finish();
            long duration = System.currentTimeMillis() - runStart;
            log.info("[Orchestrator] Run complete: outputLen={}, risk={}, steps={}, artifacts={}, duration={}ms",
                    result.output() != null ? result.output().length() : 0, risk, result.steps().size(),
                    result.artifacts().size(), duration);
            return AgentResult.success(result.output(), agentTrace, result.steps(), result.artifacts());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - runStart;
            log.error("[Orchestrator] Run failed after {}ms: {}", duration, e.getMessage(), e);
            trace.recordOutput("Error: " + e.getMessage(), RiskLevel.HIGH, false);
            trace.finish();
            throw e;
        } finally {
            LoadSkillTool.clearCurrentSession();
        }
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
                          com.harness.core.model.CancellationToken cancellationToken,
                          StreamCallback callback, Boolean enableThinking, String contextUserId,
                          AgentContext agentContext) {
        long runStart = System.currentTimeMillis();
        int attachCount = attachments != null ? attachments.size() : 0;
        log.debug("[Orchestrator] Stream run start: textLen={}, sessionId={}, attachments={}",
                text != null ? text.length() : 0, requestedSessionId, attachCount);

        TraceCollector trace = new TraceCollector(traceStore);
        String sessionId = null;

        try {
            // Layer 1: Input
            InputProcessor.InputResult input = inputProcessor.process(token, text, attachments, contextUserId);
            trace.recordInput(input.userId(), text,
                    input.message().attachments().stream().map(AgentMessage.Attachment::name).toList());

            // Build enhanced text: user text + extracted file contents
            String enhancedText = text;
            if (!input.parsedContents().isEmpty()) {
                StringBuilder sb = new StringBuilder(text);
                for (ParsedContent pc : input.parsedContents()) {
                    if (pc == null) continue; // null placeholder for non-text attachments (images, etc.)
                    sb.append("\n\n[File: ").append(pc.metadata().get("file_name")).append("]\n");
                    sb.append(pc.text());
                }
                enhancedText = sb.toString();
            }

            // Inject File content from context: resolve path, read disk, extract text for documents
            if (agentContext != null && agentContext.data() != null) {
                Object fileObj = agentContext.data().get("File");
                if (fileObj != null) {
                    List<String> filePaths = new ArrayList<>();
                    if (fileObj instanceof String s) filePaths.add(s);
                    else if (fileObj instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof String url) filePaths.add(url);
                        }
                    }

                    StringBuilder sb = new StringBuilder(enhancedText);
                    boolean hasContent = false;
                    for (String filePath : filePaths) {
                        String extracted = extractContextFileContent(filePath);
                        if (extracted != null) {
                            if (!hasContent) {
                                sb.append("\n\n[参考文件 / Reference Files]");
                                hasContent = true;
                            }
                            String name = filePath.substring(filePath.lastIndexOf('/') + 1);
                            sb.append("\n\n[File: ").append(name).append("]\n");
                            sb.append(extracted);
                            log.debug("[Orchestrator] Extracted content from context.File: {}", filePath);
                        }
                    }
                    enhancedText = sb.toString();
                }
            }

            final String ragInput = enhancedText;

            // GapAnalyzer: 动态路由判定
            AgentContext actx = agentContext != null ? agentContext : AgentContext.empty();
            GapAnalysis gapAnalysis = gapAnalyzer.analyze(ragInput, actx);
            trace.builder().metadata(gapMetadata(gapAnalysis, trace.builder().build().metadata()));

            // Layer 1.5: Session lifecycle
            List<MemoryMessage> shorttermMessages = List.of();
            List<Preference> longtermPrefs = List.of();

            // RAG retrieval; GapAnalysis controls whether retrieval happens
            final GapAnalysis finalGap = gapAnalysis;
            CompletableFuture<ContextBuilder.ContextResult> ragFuture = CompletableFuture.supplyAsync(() ->
                    contextBuilder.build(ragInput, finalGap));

            final String userId = input.userId();
            if (MemoryStoreFactory.isEnabled() && userId != null) {
                SessionLifecycleManager.LifecycleResult lifecycle = sessionLifecycle.process(input.userId(), requestedSessionId);
                sessionId = lifecycle.session().id();
                activeSessionId = sessionId;
                final String sid = sessionId;
                log.debug("[Memory] Session resolved: id={}, isNew={}, timedOut={}",
                        sessionId, lifecycle.isNewSession(), lifecycle.timedOutSessionIds().size());
                trace.builder().sessionId(sessionId);
                Map<String, String> meta = new HashMap<>(trace.builder().build().metadata());
                meta.put("session_id", sessionId);
                meta.put("session_new", String.valueOf(lifecycle.isNewSession()));
                if (!lifecycle.timedOutSessionIds().isEmpty()) {
                    meta.put("sessions_timed_out", String.join(",", lifecycle.timedOutSessionIds()));
                }
                trace.builder().metadata(meta);

                // Set session title from user's first message
                if (lifecycle.isNewSession()) {
                    String title = text.length() > 100 ? text.substring(0, 100) : text;
                    sessionStore.updateTitle(sessionId, title);
                }

                // Async: check refinement worthiness for timed-out sessions
                for (String timedOutId : lifecycle.timedOutSessionIds()) {
                    final String tid = timedOutId;
                    CompletableFuture.runAsync(() -> {
                        if (sessionLifecycle.isWorthyOfRefinement(tid)) {
                            refinementWorker.submit(tid, userId);
                        }
                    });
                }

                // Load short-term memory and long-term prefs in parallel with RAG
                CompletableFuture<List<MemoryMessage>> shorttermFuture = CompletableFuture.supplyAsync(() -> {
                    List<MemoryMessage> cached = messageCache.getIfPresent(sid);
                    if (cached != null) return cached;
                    List<MemoryMessage> loaded = messageStore.loadForContext(sid);
                    messageCache.put(sid, userId, loaded);
                    return loaded;
                });
                CompletableFuture<List<Preference>> longtermFuture = CompletableFuture.supplyAsync(() ->
                        preferenceStore.loadByUser(userId));

                CompletableFuture.allOf(shorttermFuture, longtermFuture, ragFuture).join();
                shorttermMessages = shorttermFuture.join();
                longtermPrefs = longtermFuture.join();
            } else {
                ragFuture.join();
                sessionId = requestedSessionId != null ? requestedSessionId : java.util.UUID.randomUUID().toString();
            }

            final String finalSessionId = sessionId;
            LoadSkillTool.setCurrentSession(finalSessionId);
            UpdateMemoryTool.setCurrentUserId(userId);
            UpdateMemoryTool.setCurrentSessionId(finalSessionId);

            // Layer 2: Preprocess
            ContextBuilder.ContextResult ctx = ragFuture.join();
            trace.recordPreprocess(null, ctx.ragHitIds(), null);
            String systemPrompt = buildSystemPrompt(longtermPrefs, systemPromptOverride, sessionId, Boolean.TRUE.equals(gapAnalysis.needsWebSearch()));
            trace.recordLlmMeta(chatModelProvider.modelName(), "v1");

            // Inject RAG context into user message (not system prompt) to preserve prompt cache
            String finalUserMessage = enhancedText;
            if (ctx.hasContext()) {
                finalUserMessage = enhancedText + "\n\n" + ctx.contextBlock();
            }

            // 压缩检查
            if (sessionId != null && userId != null) {
                var outcome = applyCompression(sessionId, userId, shorttermMessages, finalUserMessage, systemPrompt);
                shorttermMessages = outcome.finalMessages();

                if (outcome.hasMinor()) {
                    callback.onEvent(StreamEvent.compress("minor", outcome.minorStripped() + " 条工具消息已清理"));
                }
                if (outcome.hasMajor()) {
                    callback.onEvent(StreamEvent.compress("major",
                            outcome.majorResult().messagesBefore() + " → " + outcome.majorResult().messagesAfter() + " 条消息已压缩"));
                }

                List<MessageBlock> userBlocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT, enhancedText, null));
                messageWriteWorker.submit(sessionId, "user", userBlocks, false);
                messageCache.append(sessionId, userId, new MemoryMessage(0, sessionId, "user", userBlocks, false, null));
            }

            // Emit start event with sessionId (first event for client)
            callback.onEvent(StreamEvent.start(finalSessionId));

            // Layer 3+4: Streaming ReAct loop
            // Set parent cancellation token for sub-agents
            subAgentOrchestrator.setParentToken(cancellationToken);
            List<ChatMessage> historyChatMessages = convertToChatMessages(shorttermMessages);
            // Accumulate message blocks during ReAct loop via listener
            List<MessageBlock> blockAccumulator = new ArrayList<>();
            StringBuilder textAccumulator = new StringBuilder();

            ReActListener listener = new ReActListener() {
                @Override
                public void onStep(ReActStep step) {
                    callback.onEvent(StreamEvent.step(step));
                }

                @Override
                public void onToken(String tokenText) {
                    textAccumulator.append(tokenText);
                    callback.onEvent(StreamEvent.token(tokenText));
                }

                @Override
                public void onToolCallStart(String toolName, String arguments) {
                    callback.onEvent(StreamEvent.toolCallStart(toolName, arguments));
                }

                @Override
                public void onToolCallDone(String toolName, boolean success, long durationMs) {
                    callback.onEvent(StreamEvent.toolCallDone(toolName, success, durationMs));
                }

                @Override
                public void onArtifact(java.util.List<Artifact> artifacts) {
                    // Flush accumulated text as a TEXT block before inserting artifact
                    if (textAccumulator.length() > 0) {
                        blockAccumulator.add(new MessageBlock(MessageBlock.BlockType.TEXT, textAccumulator.toString(), null));
                        textAccumulator.setLength(0);
                    }
                    for (Artifact a : artifacts) {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("type", a.type().name());
                        if (a.mimeType() != null) meta.put("mimeType", a.mimeType());
                        if (a.name() != null) meta.put("name", a.name());
                        meta.put("previewUrl", a.previewUrl());
                        meta.put("downloadUrl", a.downloadUrl());
                        blockAccumulator.add(new MessageBlock(MessageBlock.BlockType.ARTIFACT, null, a.id(), meta));
                        callback.onEvent(StreamEvent.artifact(a));
                    }
                }
            };

            // thinking 优先级：显式 enableThinking > GapAnalysis.needsThinking > 环境变量
            Boolean effectiveThinking = enableThinking != null ? enableThinking : gapAnalysis.needsThinking();
            ReActEngine.ReActResult result = reactEngine.streamExecute(
                    systemPrompt, finalUserMessage, historyChatMessages, trace.builder(), listener, cancellationToken, effectiveThinking);
            result.steps().forEach(trace::addStep);

            // Record ReAct loop quality signals into trace metadata
            if (result.loopStats() != null) {
                ReActEngine.ReActLoopStats stats = result.loopStats();
                trace.recordReactStats(stats.outcome(), stats.rounds(), stats.toolCalls(),
                        stats.reflectionChecks());
            }

            // Flush remaining text after loop
            if (textAccumulator.length() > 0) {
                blockAccumulator.add(new MessageBlock(MessageBlock.BlockType.TEXT, textAccumulator.toString(), null));
            }

            // Accumulated blocks are authoritative — onToken/onArtifact always fire before streamExecute() returns
            List<MessageBlock> asstBlocks = blockAccumulator.isEmpty()
                    ? List.of(new MessageBlock(MessageBlock.BlockType.TEXT, result.output() != null ? result.output() : "", null))
                    : blockAccumulator;

            // Tool 消息进缓存（不落 DB），供下一轮 preprocess 小压缩
            cacheToolMessages(result, finalSessionId, userId);

            RiskLevel risk = determineRisk(result);
            trace.recordOutput(result.output(), risk, true);

            // Reply audit (async)
            final String replyText = result.output();
            CompletableFuture.runAsync(() -> {
                try {
                    ReplyAuditor.ReplyAuditResult auditResult = replyAuditor.audit(replyText);
                    if (!auditResult.passed()) {
                        log.warn("[ReplyAuditor] Audit failed: score={}, reason={}", auditResult.score(), auditResult.reason());
                    }
                } catch (Exception e) {
                    log.debug("[ReplyAuditor] Async audit failed: {}", e.getMessage());
                }
            });

            // Save AI message (async via worker)
            if (finalSessionId != null && userId != null) {
                messageWriteWorker.submit(finalSessionId, "assistant", asstBlocks, false);
                messageCache.append(finalSessionId, userId, new MemoryMessage(0, finalSessionId, "assistant", asstBlocks, false, null));
            }

            // Async: trace.finish() and sessionStore.updateLastActive()
            CompletableFuture.runAsync(() -> {
                try {
                    trace.finish();
                } catch (Exception e) {
                    log.error("[Orchestrator] Async trace.finish() failed: {}", e.getMessage());
                }
            });
            if (finalSessionId != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        sessionStore.updateLastActive(finalSessionId);
                    } catch (Exception e) {
                        log.error("[Orchestrator] Async updateLastActive failed: {}", e.getMessage());
                    }
                });
            }

            long duration = System.currentTimeMillis() - runStart;
            log.info("[Orchestrator] Stream run complete: outputLen={}, steps={}, duration={}ms",
                    result.output() != null ? result.output().length() : 0, result.steps().size(), duration);

            callback.onEvent(StreamEvent.done(
                    result.output(),
                    trace.builder().build().traceId(),
                    finalSessionId,
                    result.steps().size(),
                    result.artifacts()));

        } catch (CancellationException e) {
            long duration = System.currentTimeMillis() - runStart;
            log.info("[Orchestrator] Stream run cancelled after {}ms", duration);
            CompletableFuture.runAsync(() -> {
                try { trace.finish(); } catch (Exception ignored) {}
            });
            callback.onEvent(StreamEvent.cancelled());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - runStart;
            log.error("[Orchestrator] Stream run failed after {}ms: {}", duration, e.getMessage(), e);
            trace.recordOutput("Error: " + e.getMessage(), RiskLevel.HIGH, false);
            CompletableFuture.runAsync(() -> {
                try { trace.finish(); } catch (Exception ignored) {}
            });
            callback.onEvent(StreamEvent.error(friendlyErrorMessage(e)));
        } finally {
            LoadSkillTool.clearCurrentSession();
        }
    }

    private String buildSystemPrompt(List<Preference> longtermPrefs, String systemPromptOverride, String sessionId, boolean needsWebSearch) {
        StringBuilder sb = new StringBuilder();
        String basePrompt = (systemPromptOverride != null && !systemPromptOverride.isBlank())
                ? systemPromptOverride
                : EnvConfig.get().getString(EnvKey.SYSTEM_PROMPT,
                        "You are a helpful AI assistant with access to tools. Use tools when needed to answer questions. Think step by step. If a tool fails, try an alternative approach.");
        sb.append(basePrompt).append("\n\n");
        sb.append("IMPORTANT: After image/video generation tools succeed, do NOT include download links, file paths, image markdown syntax (![name](url)), or descriptive repetitions of the image in your text reply. The frontend automatically renders generated content as inline cards. Your text reply should only contain natural language commentary (e.g. style notes, asking if adjustments are needed).\n\n");

        if (needsWebSearch) {
            sb.append("CRITICAL: This query requires up-to-date information. You MUST call the web_search tool FIRST before composing your answer. Do NOT answer from memory alone.\n\n");
        }

        // Inject skill index — name + when-to-use description
        if (skillRegistry.size(sessionId) > 0) {
            sb.append("你有以下技能可以使用（通过 load_skill 工具加载）：\n");
            for (SkillIndex idx : skillRegistry.listAll(sessionId)) {
                sb.append("- ").append(idx.name()).append("：").append(idx.description()).append("\n");
            }
            sb.append("\nload_skill 用法：\n");
            sb.append("  - load_skill(name): 返回完整内容\n");
            sb.append("  - load_skill(name, query): 搜索并返回匹配片段（推荐，更高效）\n\n");
        }

        // Inject long-term memory (all records merged)
        if (!longtermPrefs.isEmpty()) {
            int maxChars = EnvConfig.get().getInt(EnvKey.MEMORY_LONGTERM_MAX_TOKENS, 800) * 3;
            StringBuilder memBlock = new StringBuilder("[User Memory]\n");
            for (Preference pref : longtermPrefs) {
                memBlock.append(pref.content()).append("\n");
            }
            if (memBlock.length() > maxChars) {
                memBlock.setLength(maxChars);
                memBlock.append("...\n");
                log.debug("[Memory] Long-term memory truncated to {} chars", maxChars);
            }
            sb.append(memBlock).append("\n");
        }

        return sb.toString();
    }

    private int estimateTokens(String text) {
        return TextChunker.estimateTokens(text);
    }

    private int estimateTokens(List<MemoryMessage> messages) {
        int total = 0;
        for (MemoryMessage msg : messages) {
            total += estimateTokens(msg.text());
        }
        return total;
    }

    /** 将 GapAnalysis 结果写入 trace metadata，用于事后追溯 */
    private Map<String, String> gapMetadata(GapAnalysis gap, Map<String, String> existing) {
        Map<String, String> meta = new HashMap<>(existing);
        meta.put("gap_needsKnowledgeBase", String.valueOf(gap.needsKnowledgeBase()));
        meta.put("gap_rewriteStrategy", String.valueOf(gap.rewriteStrategy()));
        meta.put("gap_needsThinking", String.valueOf(gap.needsThinking()));
        meta.put("gap_needsWebSearch", String.valueOf(gap.needsWebSearch()));
        meta.put("gap_source", String.valueOf(gap.source()));
        return meta;
    }

    /**
     * Extract a user-friendly error message from exception chain.
     * Unwraps ExecutionException/RuntimeException, parses known API error types.
     */
    public static String friendlyErrorMessage(Exception e) {
        // Unwrap exception chain
        Throwable cause = e;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return "服务内部错误，请稍后重试";
        }

        // Parse known API error patterns from JSON error bodies
        String lower = msg.toLowerCase();
        if (lower.contains("insufficient_quota") || lower.contains("exhausted")) {
            return "API 额度已用完，请充值或更换 API Key";
        }
        if (lower.contains("invalid_api_key") || lower.contains("authentication") || lower.contains("unauthorized")) {
            return "API Key 无效或已过期，请检查配置";
        }
        if (lower.contains("rate_limit") || lower.contains("too_many_requests") || lower.contains("429")) {
            return "API 请求频率超限，请稍后重试";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "API 请求超时，请稍后重试";
        }
        if (lower.contains("context_length") || lower.contains("token") && lower.contains("limit")) {
            return "输入内容超出模型上下文长度限制";
        }

        // Generic: strip JSON formatting, keep first meaningful line
        String clean = msg.replaceAll("\\{.*}", "").trim();
        if (clean.isEmpty()) clean = msg;
        // Truncate if too long
        if (clean.length() > 200) clean = clean.substring(0, 200) + "...";
        return "模型调用失败: " + clean;
    }

    /**
     * Resolve a context.File path to disk, read the file, and extract text for document types.
     * Returns null for images/video/audio (those are handled by the LLM directly).
     */
    private String extractContextFileContent(String filePath) {
        try {
            // Resolve relative path: /files/input/xxx.pdf → {knowledgeUploadDir}/input/xxx.pdf
            String uploadDir = EnvConfig.get().getString(EnvKey.KNOWLEDGE_UPLOAD_DIR, "./knowledge-uploads");
            String relativePath = filePath;
            if (relativePath.startsWith("/files/")) {
                relativePath = relativePath.substring("/files/".length());
            }
            java.nio.file.Path diskPath = java.nio.file.Path.of(uploadDir, relativePath);
            if (!java.nio.file.Files.exists(diskPath)) {
                log.debug("[Orchestrator] context.File not found on disk: {}", diskPath);
                return null;
            }

            // Guess MIME and skip non-document types (images/video/audio)
            String fileName = diskPath.getFileName().toString();
            String mimeType = com.harness.input.multimodal.impl.TextExtractorRegistry.guessMimeType(fileName);
            if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType.startsWith("audio/"))) {
                log.debug("[Orchestrator] Skipping non-document file: {} (mimeType={})", fileName, mimeType);
                return null;
            }

            byte[] data = java.nio.file.Files.readAllBytes(diskPath);
            String extracted = com.harness.input.multimodal.impl.TextExtractorRegistry.extract(data, fileName, mimeType);
            if (extracted != null && !extracted.isBlank()) {
                log.debug("[Orchestrator] Extracted {} chars from context.File: {}", extracted.length(), fileName);
                return extracted;
            }
        } catch (Exception e) {
            log.warn("[Orchestrator] Failed to extract context.File {}: {}", filePath, e.getMessage());
        }
        return null;
    }

    private RiskLevel determineRisk(ReActEngine.ReActResult result) {
        boolean hasToolErrors = result.steps().stream()
                .flatMap(s -> s.toolResults().stream())
                .anyMatch(r -> !r.success());
        return hasToolErrors ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    private void registerBuiltinTools() {
        EnvConfig cfg = EnvConfig.get();
        if (cfg.getBool(EnvKey.TOOL_WEB_SEARCH_ENABLED, true)) {
            toolRegistry.register(new WebSearchTool());
        }
        if (cfg.getBool(EnvKey.TOOL_FFMPEG_ENABLED, false)) {
            toolRegistry.register(new FfmpegTool());
        }
        // Python sandbox tool — enabled when Docker image is configured
        toolRegistry.register(new PythonSandboxTool(
                (source, name, mimeType, sessionId) -> artifactStorageService.storeFromPath(source, name, mimeType, sessionId),
                id -> artifactStore.get(id)
        ));
        // Image generation tool — only registered when API key is configured
        String imgApiKey = cfg.getString(EnvKey.TOOL_IMAGE_GEN_API_KEY, "");
        if (!imgApiKey.isBlank()) {
            toolRegistry.register(new ImageGenerationTool(new ImageGenerationTool.ArtifactStorer() {
                @Override
                public Artifact store(byte[] data, String name, String mimeType, String sessionId) {
                    return artifactStorageService.store(data, name, mimeType, sessionId);
                }

                @Override
                public byte[] loadBytes(String artifactId) {
                    return artifactStore.get(artifactId)
                            .map(a -> {
                                try {
                                    return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a.filePath()));
                                } catch (Exception e) {
                                    throw new RuntimeException("Failed to read artifact file: " + artifactId, e);
                                }
                            })
                            .orElseThrow(() -> new RuntimeException("Artifact not found: " + artifactId));
                }
            }));
        } else {
            log.info("[Orchestrator] Image generation tool disabled (no HARNESS_TOOL_IMAGE_GEN_API_KEY)");
        }
        // Video generation tool — only registered when API key + base URL are configured
        String vidApiKey = cfg.getString(EnvKey.TOOL_VIDEO_GEN_API_KEY, "");
        String vidBaseUrl = cfg.getString(EnvKey.TOOL_VIDEO_GEN_BASE_URL, "");
        if (!vidApiKey.isBlank() && !vidBaseUrl.isBlank()) {
            toolRegistry.register(new VideoGenerationTool(
                    (data, name, mimeType, sessionId) -> artifactStorageService.store(data, name, mimeType, sessionId),
                    (sessionId, artifact) -> {
                        log.info("[ArtifactCallback] Video artifact ready: {} for session {}", artifact.name(), sessionId);
                    }
            ));
        } else {
            log.info("[Orchestrator] Video generation tool disabled (no HARNESS_TOOL_VIDEO_GEN_API_KEY/BASE_URL)");
        }
        // Discovery tools — available in chat when project discovery is enabled
        if (cfg.getBool(EnvKey.PROJECT_DISCOVERY_ENABLED, true)) {
            // Use projectRoot from project-apis.json if available, otherwise "."
            Path rootPath = Path.of(".").toAbsolutePath().normalize();
            String configPath = cfg.getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
            try {
                java.nio.file.Path cfgPath = Path.of(configPath);
                if (java.nio.file.Files.exists(cfgPath)) {
                    ProjectApiConfig cfg_ = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(cfgPath.toFile(), ProjectApiConfig.class);
                    if (cfg_.projectRoot() != null && !cfg_.projectRoot().isBlank()) {
                        rootPath = Path.of(cfg_.projectRoot()).toAbsolutePath().normalize();
                        log.info("[Discovery] Using projectRoot from config: {}", rootPath);
                    }
                }
            } catch (Exception e) {
                log.warn("[Discovery] Failed to read projectRoot from config, using '.' : {}", e.getMessage());
            }
            Set<String> excludes = Set.of();
            toolRegistry.register(new CodeGlobTool(rootPath, excludes));
            toolRegistry.register(new CodeGrepTool(rootPath, excludes));
            toolRegistry.register(new ReadClassHierarchyTool(rootPath));
        }
    }

    private void registerMcpTools() {
        List<McpServerConfig> servers = McpServerConfig.loadAll();
        if (servers.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            McpToolDiscovery discovery = new McpToolDiscovery();
            discovery.discoverAndRegister(servers, toolRegistry);
        });
    }

    private void initSkills() {
        EnvConfig cfg = EnvConfig.get();
        String skillDir = cfg.getString(EnvKey.SKILL_DIR, "./skills");
        skillRegistry.loadIndex(Path.of(skillDir));
        // Always register skill tool — temporary skills may need them even without persistent skills
        toolRegistry.register(new LoadSkillTool(skillRegistry, toolRegistry));
        if (skillRegistry.size() > 0) {
            log.info("Skill system initialized: {} persistent skills from {}", skillRegistry.size(), skillDir);
        } else {
            log.info("Skill tools registered (no persistent skills found in {})", skillDir);
        }
    }

    /**
     * Load project-apis.json: register endpoint tools + re-register discovery tools with projectRoot.
     * Called at startup and by generate/reload endpoints.
     */
    public void loadProjectApiConfig() {
        EnvConfig cfg = EnvConfig.get();
        if (!cfg.getBool(EnvKey.PROJECT_DISCOVERY_ENABLED, true)) {
            log.info("[Discovery] Project API discovery disabled");
            return;
        }
        String configPath = cfg.getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
        java.nio.file.Path path = Path.of(configPath);
        if (!java.nio.file.Files.exists(path)) {
            log.debug("[Discovery] No project-apis.json found at {}, skipping", configPath);
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            ProjectApiConfig config = mapper.readValue(path.toFile(), ProjectApiConfig.class);
            toolRegistry.loadFromConfig(config);

            // Re-register discovery tools with the projectRoot from config
            if (config.projectRoot() != null && !config.projectRoot().isBlank()) {
                Path projectRoot = Path.of(config.projectRoot()).toAbsolutePath().normalize();
                Set<String> excludes = Set.of();
                toolRegistry.register(new CodeGlobTool(projectRoot, excludes));
                toolRegistry.register(new CodeGrepTool(projectRoot, excludes));
                toolRegistry.register(new ReadClassHierarchyTool(projectRoot));
                log.info("[Discovery] Re-registered discovery tools with projectRoot={}", projectRoot);
            }

            log.info("[Discovery] Loaded project APIs from {}: {} endpoints",
                    configPath, config.endpoints().size());
        } catch (Exception e) {
            log.error("[Discovery] Failed to load project-apis.json: {}", e.getMessage());
        }
    }

    /**
     * Build skill content string for re-injection after major compression.
     * Formats skill as a system message that preserves the full skill instructions.
     */
    private String buildSkillContentForReinjection(com.harness.core.model.Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Skill: ").append(skill.name()).append("]\n");
        sb.append("[Description: ").append(skill.description()).append("]\n");
        if (skill.version() != null) {
            sb.append("[Version: ").append(skill.version()).append("]\n");
        }
        sb.append("\n[Instructions]\n");
        sb.append(skill.systemPrompt()).append("\n");
        if (skill.tools() != null && !skill.tools().isEmpty()) {
            sb.append("\n[Bound Tools: ").append(String.join(", ", skill.tools())).append("]\n");
        }
        if (skill.parameters() != null && !skill.parameters().isEmpty()) {
            sb.append("[Parameters: ");
            List<String> paramPairs = new ArrayList<>();
            for (Map.Entry<String, Object> entry : skill.parameters().entrySet()) {
                paramPairs.add(entry.getKey() + "=" + entry.getValue());
            }
            sb.append(String.join(", ", paramPairs));
            sb.append("]\n");
        }
        return sb.toString();
    }

    // Expose model providers for external use (e.g., direct vision/voice calls)
    public ChatModelProvider chatModel() { return chatModelProvider; }
    public VisionModelProvider visionModel() { return visionModelProvider; }
    public VoiceModelProvider voiceModel() { return voiceModelProvider; }
    public EmbeddingModelProvider embeddingModel() { return embeddingModelProvider; }
    public RerankModelProvider rerankModel() { return rerankModelProvider; }
    public RealtimeModelProvider realtimeModel() { return realtimeModelProvider; }

    // Expose memory stores for external use (e.g., cleanup scheduler)
    public SessionStore sessionStore() { return sessionStore; }
    public MessageStore messageStore() { return messageStore; }
    public PreferenceStore preferenceStore() { return preferenceStore; }
    public SessionLifecycleManager sessionLifecycle() { return sessionLifecycle; }
    public PreferenceRefinementWorker refinementWorker() { return refinementWorker; }
    public SubAgentOrchestrator subAgentOrchestrator() { return subAgentOrchestrator; }
    public SessionMessageCache messageCache() { return messageCache; }
    public MessageWriteWorker messageWriteWorker() { return messageWriteWorker; }
    public SkillRegistry skillRegistry() { return skillRegistry; }
    public TraceStore traceStore() { return traceStore; }
    public com.harness.preprocess.rag.VectorStore vectorStore() { return contextBuilder.vectorStore(); }

    // Expose artifact subsystem
    public ArtifactStore artifactStore() { return artifactStore; }
    public ArtifactStorageService artifactStorageService() { return artifactStorageService; }


    /**
     * Convert MemoryMessage list to LangChain4j ChatMessage list for ReAct history injection.
     * Summary rows are converted to AiMessage to preserve compressed context.
     * Skill content (system messages starting with "[Skill:") is preserved as AiMessage.
     */
    private List<ChatMessage> convertToChatMessages(List<MemoryMessage> memoryMessages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (MemoryMessage msg : memoryMessages) {
            String text = msg.text();
            if (msg.isSummary()) {
                chatMessages.add(AiMessage.from("[Previous conversation summary]\n" + text));
                continue;
            }
            switch (msg.role()) {
                case "user" -> chatMessages.add(UserMessage.from(text));
                case "assistant" -> chatMessages.add(AiMessage.from(text));
                case "system" -> {
                    if (text != null && text.startsWith("[Skill:")) {
                        chatMessages.add(AiMessage.from(text));
                    }
                }
            }
        }
        return chatMessages;
    }

    /**
     * 从 ReActResult 中提取 tool 消息，追加到缓存（不落 DB）。
     * 下一轮 preprocess 时可被小压缩清理。
     */
    private void cacheToolMessages(ReActEngine.ReActResult result, String sessionId, String userId) {
        if (sessionId == null || userId == null) return;
        for (ReActStep step : result.steps()) {
            if (step.toolCalls() == null || step.toolCalls().isEmpty()) continue;
            String toolCallDesc = step.toolCalls().stream()
                    .map(tc -> tc.toolName() + "(" + tc.arguments() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("");
            List<MessageBlock> toolCallBlocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT, "[Tool call] " + toolCallDesc, null));
            messageCache.append(sessionId, userId,
                    new MemoryMessage(0, sessionId, "assistant", toolCallBlocks, false, null));
            if (step.toolResults() != null) {
                for (ToolResult tr : step.toolResults()) {
                    String content = tr.success() ? tr.output() : "ERROR: " + tr.error();
                    List<MessageBlock> toolResultBlocks = List.of(new MessageBlock(MessageBlock.BlockType.TEXT, "[" + tr.toolName() + "] " + content, null));
                    messageCache.append(sessionId, userId,
                            new MemoryMessage(0, sessionId, "tool", toolResultBlocks, false, null));
                }
            }
        }
    }

    /**
     * 压缩结果：供调用方决定如何上报。
     */
    record CompressionOutcome(
            int minorStripped,
            MemoryCompressor.CompressionResult majorResult,
            List<MemoryMessage> finalMessages
    ) {
        boolean hasMinor() { return minorStripped > 0; }
        boolean hasMajor() { return majorResult.type() != MemoryCompressor.CompressionResult.CompressionType.NONE; }
    }

    /**
     * 统一压缩流程：总上下文达到阈值时先小压缩，仍超则大压缩。
     * 返回 CompressionOutcome 供调用方上报 SSE 或写 trace metadata。
     */
    private CompressionOutcome applyCompression(
            String sessionId, String userId,
            List<MemoryMessage> shorttermMessages,
            String finalUserMessage, String systemPrompt) {

        int totalBudget = chatModelProvider.chatModel() != null ? chatModelProvider.contextWindow() : 8000;
        int shorttermTokens = estimateTokens(shorttermMessages);
        int inputTokens = estimateTokens(finalUserMessage);
        int systemTokens = estimateTokens(systemPrompt);
        int totalUsed = shorttermTokens + inputTokens + systemTokens;

        int majorThreshold = EnvConfig.get().getInt(EnvKey.CTX_COMPRESS_MAJOR, 85);
        int usagePercent = (int) (totalUsed * 100.0 / totalBudget);
        int minorStripped = 0;

        // 小压缩：达到阈值时清理 tool 消息
        if (usagePercent >= majorThreshold) {
            minorStripped = stripToolMessagesFromCache(sessionId, userId);
            if (minorStripped > 0) {
                shorttermMessages = messageCache.getIfPresent(sessionId);
                if (shorttermMessages == null) {
                    shorttermMessages = messageStore.loadForContext(sessionId);
                }
                shorttermTokens = estimateTokens(shorttermMessages);
                totalUsed = shorttermTokens + inputTokens + systemTokens;
                usagePercent = (int) (totalUsed * 100.0 / totalBudget);
            }
        }

        // 大压缩：小压缩后仍超过（阈值-10）% 时触发
        var majorResult = new MemoryCompressor.CompressionResult(
                MemoryCompressor.CompressionResult.CompressionType.NONE, 0, 0);
        if (usagePercent >= majorThreshold - 10) {
            majorResult = memoryCompressor.compressIfNeeded(
                    sessionId, shorttermMessages, shorttermTokens, totalUsed, totalBudget);
        }

        // 大压缩后重建缓存 + 重新注入 skill
        if (majorResult.type() != MemoryCompressor.CompressionResult.CompressionType.NONE) {
            log.info("[Memory] Compression triggered: type={}, before={}, after={}",
                    majorResult.type(), majorResult.messagesBefore(), majorResult.messagesAfter());
            shorttermMessages = messageStore.loadForContext(sessionId);
            messageCache.put(sessionId, userId, shorttermMessages);

            List<com.harness.core.model.Skill> loadedSkills = skillRegistry.getLoadedSkills(sessionId);
            if (!loadedSkills.isEmpty()) {
                log.debug("[Memory] Re-injecting {} loaded skills after major compression", loadedSkills.size());
                List<MemoryMessage> skillMessages = new ArrayList<>(shorttermMessages);
                for (com.harness.core.model.Skill skill : loadedSkills) {
                    String skillContent = buildSkillContentForReinjection(skill);
                    skillMessages.add(new MemoryMessage(0, sessionId, "system", List.of(new MessageBlock(MessageBlock.BlockType.TEXT, skillContent, null)), false, null));
                }
                shorttermMessages = skillMessages;
                messageCache.put(sessionId, userId, shorttermMessages);
            }
        }

        return new CompressionOutcome(minorStripped, majorResult, shorttermMessages);
    }

    /**
     * 小压缩：从缓存中删除 tool 消息，返回删除的消息数。
     * 在预处理层调用，先于大压缩。
     */
    private int stripToolMessagesFromCache(String sessionId, String userId) {
        List<MemoryMessage> cached = messageCache.getIfPresent(sessionId);
        if (cached == null || cached.isEmpty()) return 0;
        int before = cached.size();
        List<MemoryMessage> stripped = cached.stream()
                .filter(m -> !"tool".equals(m.role()))
                .filter(m -> !(m.role().equals("assistant") && m.text().startsWith("[Tool call]")))
                .toList();
        if (stripped.size() < before) {
            int removed = before - stripped.size();
            messageCache.put(sessionId, userId, stripped);
            log.info("[Memory] Minor compression: stripped {} tool messages from cache ({} → {})", removed, before, stripped.size());
            return removed;
        }
        return 0;
    }

    public void shutdown() {
        subAgentOrchestrator.shutdown();
        if (cleanupScheduler != null) cleanupScheduler.stop();
        if (messageWriteWorker != null) messageWriteWorker.stop();  // Flush pending writes before stopping
        if (refinementWorker != null) refinementWorker.stop();
        messageCache.evictExpired();
        skillRegistry.evictExpired();
        traceStore.close();
        com.harness.env.PgConnectionPool.shutdown();
        com.harness.env.MysqlConnectionPool.shutdown();
        com.harness.env.RedisConnectionPool.shutdown();
        log.info("Agent shut down");
    }
}
