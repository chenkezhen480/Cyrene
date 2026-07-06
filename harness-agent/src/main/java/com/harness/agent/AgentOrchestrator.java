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
import com.harness.preprocess.memory.*;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.builtin.FfmpegTool;
import com.harness.tool.builtin.UpdateMemoryTool;
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

    // 6 model providers
    private final ChatModelProvider chatModelProvider;
    private final VisionModelProvider visionModelProvider;
    private final VoiceModelProvider voiceModelProvider;
    private final EmbeddingModelProvider embeddingModelProvider;
    private final RerankModelProvider rerankModelProvider;
    private final RealtimeModelProvider realtimeModelProvider;

    private final InputProcessor inputProcessor;
    private final ContextBuilder contextBuilder;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ReActEngine reactEngine;
    private final TraceStore traceStore;
    private final ReplyAuditor replyAuditor;

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

        log.info("Agent initialized: chat={}, vision={}, voice={}, embedding={}, rerank={}, tools={}, memory={}",
                chatModelProvider.providerName(),
                visionModelProvider.providerName(),
                voiceModelProvider.providerName(),
                embeddingModelProvider.providerName(),
                rerankModelProvider.providerName(),
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
                    sb.append("\n\n[File: ").append(pc.metadata().get("file_name")).append("]\n");
                    sb.append(pc.text());
                }
                enhancedText = sb.toString();
            }

            // Pre-detect skill files from attachments (register after sessionId is resolved)
            List<com.harness.core.model.Skill> pendingSkills = new ArrayList<>();
            if (attachments != null) {
                for (var attachment : attachments) {
                    if (attachment.name() != null && attachment.name().endsWith(".md")) {
                        String content = extractAttachmentContent(attachment);
                        if (content != null && SkillLoader.isSkillFile(content)) {
                            com.harness.core.model.Skill skill = SkillLoader.loadFromContent(content);
                            if (skill != null) {
                                pendingSkills.add(skill);
                            }
                        }
                    }
                }
            }

            final String ragInput = enhancedText;

            // ===== Layer 1.5: Session lifecycle =====
            List<MemoryMessage> shorttermMessages = List.of();
            List<Preference> longtermPrefs = List.of();

            // RAG retrieval always runs; memory loading is conditional
            CompletableFuture<ContextBuilder.ContextResult> ragFuture = CompletableFuture.supplyAsync(() ->
                    contextBuilder.build(ragInput));

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

            // Register pending skill files with resolved sessionId
            for (com.harness.core.model.Skill pending : pendingSkills) {
                skillRegistry.addTemporary(sessionId, pending);
            }

            // Set ThreadLocal for skill tools session-scoped lookup
            final String finalSessionId = sessionId;
            LoadSkillTool.setCurrentSession(finalSessionId);
            UpdateMemoryTool.setCurrentUserId(userId);
            UpdateMemoryTool.setCurrentSessionId(finalSessionId);

            // ===== Layer 2: Preprocess =====
            ContextBuilder.ContextResult ctx = ragFuture.join();
            trace.recordPreprocess(null, ctx.ragHitIds(), null);
            String systemPrompt = buildSystemPrompt(longtermPrefs, systemPromptOverride, sessionId);
            log.debug("[Orchestrator] System prompt: {} chars, longterm={}",
                    systemPrompt.length(), !longtermPrefs.isEmpty());

            // Inject RAG context into user message (not system prompt) to preserve prompt cache
            String finalUserMessage = enhancedText;
            if (ctx.hasContext()) {
                finalUserMessage = enhancedText + "\n\n" + ctx.contextBlock();
            }
            trace.recordLlmMeta(chatModelProvider.modelName(), "v1");

            // 压缩检查：先小压缩（删缓存中的 tool 消息），再大压缩（AI 摘要）
            int totalBudget = chatModelProvider.chatModel() != null ? chatModelProvider.contextWindow() : 8000;
            if (sessionId != null && userId != null) {
                // 小压缩：从缓存中删除 tool 消息
                int stripped = stripToolMessagesFromCache(sessionId, userId);
                if (stripped > 0) {
                    // 缓存已更新，重新加载 shorttermMessages
                    shorttermMessages = messageCache.getIfPresent(sessionId);
                    if (shorttermMessages == null) {
                        shorttermMessages = messageStore.loadForContext(sessionId);
                    }
                }

                int shorttermTokens = estimateTokens(shorttermMessages);
                int inputTokens = estimateTokens(finalUserMessage);  // includes RAG context
                int systemTokens = estimateTokens(systemPrompt);
                int totalUsed = shorttermTokens + inputTokens + systemTokens;
                log.debug("[Orchestrator] Token estimate: shortterm={}, input={}, system={}, total={}/{}",
                        shorttermTokens, inputTokens, systemTokens, totalUsed, totalBudget);

                // 大压缩：小压缩后仍超过（大压缩阈值-10）% 时触发
                int majorThreshold = EnvConfig.get().getInt(EnvKey.CTX_COMPRESS_MAJOR, 85);
                var compressResult = new MemoryCompressor.CompressionResult(
                        MemoryCompressor.CompressionResult.CompressionType.NONE, 0, 0);
                if ((int) (totalUsed * 100.0 / totalBudget) >= majorThreshold - 10) {
                    compressResult = memoryCompressor.compressIfNeeded(
                            sessionId, shorttermMessages, shorttermTokens, totalUsed, totalBudget);
                }
                if (compressResult.type() != MemoryCompressor.CompressionResult.CompressionType.NONE) {
                    log.info("[Memory] Compression triggered: type={}, before={}, after={}",
                            compressResult.type(), compressResult.messagesBefore(), compressResult.messagesAfter());
                    Map<String, String> meta = new HashMap<>(trace.builder().build().metadata());
                    meta.put("compression_type", compressResult.type().name());
                    meta.put("messages_before", String.valueOf(compressResult.messagesBefore()));
                    meta.put("messages_after", String.valueOf(compressResult.messagesAfter()));
                    trace.builder().metadata(meta);
                    // 压缩后重建缓存（从DB加载含摘要的状态）
                    shorttermMessages = messageStore.loadForContext(sessionId);
                    messageCache.put(sessionId, userId, shorttermMessages);

                    // 大压缩后重新注入已加载的skill内容（skill内容不在DB中，需要重新加载）
                    List<com.harness.core.model.Skill> loadedSkills = skillRegistry.getLoadedSkills(sessionId);
                    if (!loadedSkills.isEmpty()) {
                        log.debug("[Memory] Re-injecting {} loaded skills after major compression", loadedSkills.size());
                        List<MemoryMessage> skillMessages = new ArrayList<>(shorttermMessages);
                        for (com.harness.core.model.Skill skill : loadedSkills) {
                            String skillContent = buildSkillContentForReinjection(skill);
                            skillMessages.add(new MemoryMessage(0, sessionId, "system", skillContent, false, null));
                        }
                        shorttermMessages = skillMessages;
                        messageCache.put(sessionId, userId, shorttermMessages);
                    }
                }

                // 用户消息：异步写DB + 同步更新缓存
                messageWriteWorker.submit(sessionId, "user", enhancedText, false);
                messageCache.append(sessionId, userId, new MemoryMessage(0, sessionId, "user", enhancedText, false, null));
                sessionStore.updateLastActive(sessionId);
            }

            // ===== Layer 3+4: ReAct loop（AI决策 + 工具执行 + 小压缩去除工具块） =====
            // Set parent cancellation token for sub-agents
            subAgentOrchestrator.setParentToken(cancellationToken);
            List<ChatMessage> historyChatMessages = convertToChatMessages(shorttermMessages);
            ReActEngine.ReActResult result = reactEngine.execute(systemPrompt, finalUserMessage, historyChatMessages, trace.builder(), null, cancellationToken, enableThinking);
            result.steps().forEach(trace::addStep);

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

            // ===== 后处理：保存AI回复 =====
            if (sessionId != null && userId != null) {
                messageWriteWorker.submit(sessionId, "assistant", result.output(), false);
                messageCache.append(sessionId, userId, new MemoryMessage(0, sessionId, "assistant", result.output(), false, null));
                sessionStore.updateLastActive(sessionId);
            }

            AgentTrace agentTrace = trace.finish();
            long duration = System.currentTimeMillis() - runStart;
            log.info("[Orchestrator] Run complete: outputLen={}, risk={}, steps={}, duration={}ms",
                    result.output() != null ? result.output().length() : 0, risk, result.steps().size(), duration);
            return AgentResult.success(result.output(), agentTrace, result.steps());

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
                    sb.append("\n\n[File: ").append(pc.metadata().get("file_name")).append("]\n");
                    sb.append(pc.text());
                }
                enhancedText = sb.toString();
            }

            // Pre-detect skill files from attachments (register after sessionId is resolved)
            List<com.harness.core.model.Skill> pendingSkills = new ArrayList<>();
            if (attachments != null) {
                for (var attachment : attachments) {
                    if (attachment.name() != null && attachment.name().endsWith(".md")) {
                        String content = extractAttachmentContent(attachment);
                        if (content != null && SkillLoader.isSkillFile(content)) {
                            com.harness.core.model.Skill skill = SkillLoader.loadFromContent(content);
                            if (skill != null) {
                                pendingSkills.add(skill);
                            }
                        }
                    }
                }
            }

            final String ragInput = enhancedText;

            // Layer 1.5: Session lifecycle
            List<MemoryMessage> shorttermMessages = List.of();
            List<Preference> longtermPrefs = List.of();

            // RAG retrieval always runs; memory loading is conditional
            CompletableFuture<ContextBuilder.ContextResult> ragFuture = CompletableFuture.supplyAsync(() ->
                    contextBuilder.build(ragInput));

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

            // Register pending skill files with resolved sessionId
            for (com.harness.core.model.Skill pending : pendingSkills) {
                skillRegistry.addTemporary(sessionId, pending);
            }
            final String finalSessionId = sessionId;
            LoadSkillTool.setCurrentSession(finalSessionId);
            UpdateMemoryTool.setCurrentUserId(userId);
            UpdateMemoryTool.setCurrentSessionId(finalSessionId);

            // Layer 2: Preprocess
            ContextBuilder.ContextResult ctx = ragFuture.join();
            trace.recordPreprocess(null, ctx.ragHitIds(), null);
            String systemPrompt = buildSystemPrompt(longtermPrefs, systemPromptOverride, sessionId);
            trace.recordLlmMeta(chatModelProvider.modelName(), "v1");

            // Inject RAG context into user message (not system prompt) to preserve prompt cache
            String finalUserMessage = enhancedText;
            if (ctx.hasContext()) {
                finalUserMessage = enhancedText + "\n\n" + ctx.contextBlock();
            }

            // 压缩检查：先小压缩（删缓存中的 tool 消息），再大压缩（AI 摘要）
            int totalBudget = chatModelProvider.chatModel() != null ? chatModelProvider.contextWindow() : 8000;
            if (sessionId != null && userId != null) {
                // 小压缩：从缓存中删除 tool 消息
                int stripped = stripToolMessagesFromCache(sessionId, userId);
                if (stripped > 0) {
                    callback.onEvent(StreamEvent.compress("minor", stripped + " 条工具消息已清理"));
                    shorttermMessages = messageCache.getIfPresent(sessionId);
                    if (shorttermMessages == null) {
                        shorttermMessages = messageStore.loadForContext(sessionId);
                    }
                }

                int shorttermTokens = estimateTokens(shorttermMessages);
                int inputTokens = estimateTokens(finalUserMessage);  // includes RAG context
                int systemTokens = estimateTokens(systemPrompt);
                int totalUsed = shorttermTokens + inputTokens + systemTokens;

                // 大压缩：小压缩后仍超过（大压缩阈值-10）% 时触发
                int majorThreshold = EnvConfig.get().getInt(EnvKey.CTX_COMPRESS_MAJOR, 85);
                var compressResult = new MemoryCompressor.CompressionResult(
                        MemoryCompressor.CompressionResult.CompressionType.NONE, 0, 0);
                if ((int) (totalUsed * 100.0 / totalBudget) >= majorThreshold - 10) {
                    compressResult = memoryCompressor.compressIfNeeded(
                            sessionId, shorttermMessages, shorttermTokens, totalUsed, totalBudget);
                }
                if (compressResult.type() != MemoryCompressor.CompressionResult.CompressionType.NONE) {
                    log.info("[Memory] Compression triggered: type={}, before={}, after={}",
                            compressResult.type(), compressResult.messagesBefore(), compressResult.messagesAfter());
                    callback.onEvent(StreamEvent.compress("major",
                            compressResult.messagesBefore() + " → " + compressResult.messagesAfter() + " 条消息已压缩"));
                    shorttermMessages = messageStore.loadForContext(sessionId);
                    messageCache.put(sessionId, userId, shorttermMessages);

                    // 大压缩后重新注入已加载的skill内容（skill内容不在DB中，需要重新加载）
                    List<com.harness.core.model.Skill> loadedSkills = skillRegistry.getLoadedSkills(sessionId);
                    if (!loadedSkills.isEmpty()) {
                        log.debug("[Memory] Re-injecting {} loaded skills after major compression", loadedSkills.size());
                        List<MemoryMessage> skillMessages = new ArrayList<>(shorttermMessages);
                        for (com.harness.core.model.Skill skill : loadedSkills) {
                            String skillContent = buildSkillContentForReinjection(skill);
                            skillMessages.add(new MemoryMessage(0, sessionId, "system", skillContent, false, null));
                        }
                        shorttermMessages = skillMessages;
                        messageCache.put(sessionId, userId, shorttermMessages);
                    }
                }

                messageWriteWorker.submit(sessionId, "user", enhancedText, false);
                messageCache.append(sessionId, userId, new MemoryMessage(0, sessionId, "user", enhancedText, false, null));
            }

            // Emit start event with sessionId (first event for client)
            callback.onEvent(StreamEvent.start(finalSessionId));

            // Layer 3+4: Streaming ReAct loop
            // Set parent cancellation token for sub-agents
            subAgentOrchestrator.setParentToken(cancellationToken);
            List<ChatMessage> historyChatMessages = convertToChatMessages(shorttermMessages);
            ReActListener listener = new ReActListener() {
                @Override
                public void onStep(ReActStep step) {
                    callback.onEvent(StreamEvent.step(step));
                }

                @Override
                public void onToken(String tokenText) {
                    callback.onEvent(StreamEvent.token(tokenText));
                }
            };

            ReActEngine.ReActResult result = reactEngine.streamExecute(
                    systemPrompt, finalUserMessage, historyChatMessages, trace.builder(), listener, cancellationToken, enableThinking);
            result.steps().forEach(trace::addStep);

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

            // Post-processing: save AI message (async via worker)
            if (finalSessionId != null && userId != null) {
                messageWriteWorker.submit(finalSessionId, "assistant", result.output(), false);
                messageCache.append(finalSessionId, userId, new MemoryMessage(0, finalSessionId, "assistant", result.output(), false, null));
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
                    result.steps().size()));

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - runStart;
            log.error("[Orchestrator] Stream run failed after {}ms: {}", duration, e.getMessage(), e);
            trace.recordOutput("Error: " + e.getMessage(), RiskLevel.HIGH, false);
            CompletableFuture.runAsync(() -> {
                try { trace.finish(); } catch (Exception ignored) {}
            });
            callback.onEvent(StreamEvent.error(e.getMessage()));
        } finally {
            LoadSkillTool.clearCurrentSession();
        }
    }

    private String buildSystemPrompt(List<Preference> longtermPrefs, String systemPromptOverride, String sessionId) {
        StringBuilder sb = new StringBuilder();
        String basePrompt = (systemPromptOverride != null && !systemPromptOverride.isBlank())
                ? systemPromptOverride
                : EnvConfig.get().getString(EnvKey.SYSTEM_PROMPT,
                        "You are a helpful AI assistant with access to tools. Use tools when needed to answer questions. Think step by step. If a tool fails, try an alternative approach.");
        sb.append(basePrompt).append("\n\n");

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
            total += estimateTokens(msg.content());
        }
        return total;
    }

    private String extractAttachmentContent(MultimodalParser.RawAttachment attachment) {
        if (attachment.data() == null) return null;
        try {
            return new String(attachment.data(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Failed to extract attachment content: {}", e.getMessage());
            return null;
        }
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
        // Discovery tools — available in chat when project discovery is enabled
        if (cfg.getBool(EnvKey.PROJECT_DISCOVERY_ENABLED, true)) {
            Path rootPath = Path.of(".").toAbsolutePath().normalize();
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
     * Load project-apis.json and register confirmed endpoints as HttpApiTools.
     */
    private void loadProjectApiConfig() {
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
            log.info("[Discovery] Loaded project APIs from {}: {} endpoints",
                    configPath, config.endpoints().size());
        } catch (Exception e) {
            log.error("[Discovery] Failed to load project-apis.json: {}", e.getMessage());
        }
    }

    /**
     * Hot-reload project API config (called by server endpoint).
     */
    public void reloadProjectApiConfig() {
        EnvConfig cfg = EnvConfig.get();
        String configPath = cfg.getString(EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
        java.nio.file.Path path = Path.of(configPath);
        if (!java.nio.file.Files.exists(path)) {
            toolRegistry.hotReload(new ProjectApiConfig(null, null, null, List.of()));
            log.info("[Discovery] project-apis.json not found, cleared config");
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            ProjectApiConfig config = mapper.readValue(path.toFile(), ProjectApiConfig.class);
            toolRegistry.hotReload(config);
            log.info("[Discovery] Hot-reloaded project APIs: {} endpoints", config.endpoints().size());
        } catch (Exception e) {
            log.error("[Discovery] Failed to hot-reload project-apis.json: {}", e.getMessage());
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
    public SkillRegistry skillRegistry() { return skillRegistry; }
    public TraceStore traceStore() { return traceStore; }
    public com.harness.preprocess.rag.VectorStore vectorStore() { return contextBuilder.vectorStore(); }

    /**
     * Convert MemoryMessage list to LangChain4j ChatMessage list for ReAct history injection.
     * Summary rows are converted to AiMessage to preserve compressed context.
     * Skill content (system messages starting with "[Skill:") is preserved as AiMessage.
     */
    private List<ChatMessage> convertToChatMessages(List<MemoryMessage> memoryMessages) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (MemoryMessage msg : memoryMessages) {
            if (msg.isSummary()) {
                // Compressed summary: inject as AiMessage so LLM sees prior context
                chatMessages.add(AiMessage.from("[Previous conversation summary]\n" + msg.content()));
                continue;
            }
            switch (msg.role()) {
                case "user" -> chatMessages.add(UserMessage.from(msg.content()));
                case "assistant" -> chatMessages.add(AiMessage.from(msg.content()));
                case "system" -> {
                    // Skill content re-injected after major compression
                    if (msg.content() != null && msg.content().startsWith("[Skill:")) {
                        chatMessages.add(AiMessage.from(msg.content()));
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
            // 保存 assistant 的工具调用请求
            String toolCallDesc = step.toolCalls().stream()
                    .map(tc -> tc.toolName() + "(" + tc.arguments() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("");
            messageCache.append(sessionId, userId,
                    new MemoryMessage(0, sessionId, "assistant", "[Tool call] " + toolCallDesc, false, null));
            // 保存工具执行结果
            if (step.toolResults() != null) {
                for (ToolResult tr : step.toolResults()) {
                    String content = tr.success() ? tr.output() : "ERROR: " + tr.error();
                    messageCache.append(sessionId, userId,
                            new MemoryMessage(0, sessionId, "tool", "[" + tr.toolName() + "] " + content, false, null));
                }
            }
        }
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
                .filter(m -> !(m.role().equals("assistant") && m.content() != null && m.content().startsWith("[Tool call]")))
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
