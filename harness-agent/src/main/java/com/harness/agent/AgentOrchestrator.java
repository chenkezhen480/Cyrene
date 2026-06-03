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
import com.harness.input.InputProcessor;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.preprocess.ContextBuilder;
import com.harness.preprocess.memory.*;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.builtin.FfmpegTool;
import com.harness.tool.builtin.WebSearchTool;
import com.harness.tool.mcp.McpServerConfig;
import com.harness.tool.mcp.McpToolDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private String activeSessionId;  // CLI mode: reuse across calls

    public AgentOrchestrator() {
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
        this.contextBuilder = new ContextBuilder(rerankModelProvider, chatModelProvider, embeddingModelProvider);

        // Layer 3: Tools
        this.toolRegistry = new ToolRegistry();
        registerBuiltinTools();
        registerMcpTools();
        this.toolExecutor = new ToolExecutor(toolRegistry);

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

        // Memory subsystem
        this.sessionStore = MemoryStoreFactory.createSessionStore();
        this.messageStore = MemoryStoreFactory.createMessageStore();
        this.preferenceStore = MemoryStoreFactory.createPreferenceStore();
        this.sessionLifecycle = new SessionLifecycleManager(sessionStore, messageStore);
        this.refinementWorker = new PreferenceRefinementWorker(messageStore, preferenceStore, chatModelProvider);
        this.memoryCompressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);
        this.messageCache = new SessionMessageCache();
        this.messageWriteWorker = new MessageWriteWorker(messageStore);

        // Start background workers if memory enabled
        if (MemoryStoreFactory.isEnabled()) {
            refinementWorker.start();
            messageWriteWorker.start();
            cleanupScheduler = new SessionCleanupScheduler(sessionStore, sessionLifecycle, refinementWorker);
            cleanupScheduler.start();
        } else {
            cleanupScheduler = null;
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
        return run(token, text, attachments, null, null, null);
    }

    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments, String requestedSessionId) {
        return run(token, text, attachments, requestedSessionId, null, null);
    }

    /**
     * Run agent with full control over session, system prompt override, and cancellation.
     *
     * @param token auth token
     * @param text user input text
     * @param attachments optional attachments
     * @param requestedSessionId optional session ID (null = reuse active)
     * @param systemPromptOverride optional system prompt override (null = use env default)
     * @param cancellationToken optional cancellation token for aborting in-progress runs
     */
    public AgentResult run(String token, String text, List<MultimodalParser.RawAttachment> attachments,
                           String requestedSessionId, String systemPromptOverride,
                           com.harness.core.model.CancellationToken cancellationToken) {
        long runStart = System.currentTimeMillis();
        int attachCount = attachments != null ? attachments.size() : 0;
        log.info("[Orchestrator] Run start: textLen={}, sessionId={}, attachments={}",
                text != null ? text.length() : 0, requestedSessionId, attachCount);

        TraceCollector trace = new TraceCollector(traceStore);

        try {
            // Layer 1: Input
            InputProcessor.InputResult input = inputProcessor.process(token, text, attachments);
            trace.recordInput(input.userId(), text,
                    input.message().attachments().stream().map(AgentMessage.Attachment::name).toList());

            // ===== Layer 1.5: Session lifecycle =====
            String sessionId = null;
            List<MemoryMessage> shorttermMessages = List.of();
            List<Preference> longtermPrefs = List.of();
            boolean compressed = false;
            if (MemoryStoreFactory.isEnabled() && input.userId() != null) {
                String effectiveSessionId = requestedSessionId != null ? requestedSessionId : activeSessionId;
                SessionLifecycleManager.LifecycleResult lifecycle = sessionLifecycle.process(input.userId(), effectiveSessionId);
                sessionId = lifecycle.session().id();
                activeSessionId = sessionId;
                log.info("[Memory] Session resolved: id={}, isNew={}, timedOut={}",
                        sessionId, lifecycle.isNewSession(), lifecycle.timedOutSessionIds().size());
                trace.builder().sessionId(sessionId);
                Map<String, String> meta = new HashMap<>(trace.builder().build().metadata());
                meta.put("session_id", sessionId);
                meta.put("session_new", String.valueOf(lifecycle.isNewSession()));
                if (!lifecycle.timedOutSessionIds().isEmpty()) {
                    meta.put("sessions_timed_out", String.join(",", lifecycle.timedOutSessionIds()));
                }
                trace.builder().metadata(meta);

                for (String timedOutId : lifecycle.timedOutSessionIds()) {
                    if (sessionLifecycle.isWorthyOfRefinement(timedOutId)) {
                        refinementWorker.submit(timedOutId, input.userId());
                        meta.put("refinement_submitted", timedOutId);
                    }
                }

                // Load short-term memory (旧消息，cache-first)
                shorttermMessages = messageCache.getIfPresent(sessionId);
                if (shorttermMessages == null) {
                    shorttermMessages = messageStore.loadForContext(sessionId);
                    messageCache.put(sessionId, shorttermMessages);
                } else {
                    log.debug("Cache hit for session: {}", sessionId);
                }

                // Load long-term memory (user preferences)
                longtermPrefs = preferenceStore.loadByUser(input.userId());
                log.debug("[Memory] Loaded {} long-term preferences for user {}", longtermPrefs.size(), input.userId());
            }

            // ===== Layer 2: Preprocess =====
            // RAG知识库检索
            ContextBuilder.ContextResult ctx = contextBuilder.build(text);
            trace.recordPreprocess(null, ctx.ragHitIds(), null);

            // system消息组装 + 长期记忆注入
            String systemPrompt = buildSystemPrompt(ctx, longtermPrefs, systemPromptOverride);
            log.debug("[Orchestrator] System prompt: {} chars, rag={}, longterm={}",
                    systemPrompt.length(), ctx.hasContext(), !longtermPrefs.isEmpty());
            trace.recordLlmMeta(chatModelProvider.modelName(), "v1");

            // 大压缩检查（旧消息 + 估算新用户消息 + RAG + system + 长期记忆）
            if (sessionId != null) {
                int shorttermTokens = estimateTokens(shorttermMessages);
                int ragTokens = ctx.contextBlock() != null ? estimateTokens(ctx.contextBlock()) : 0;
                int inputTokens = estimateTokens(text);  // 只估算用户消息，AI回复未知
                int systemTokens = estimateTokens(systemPrompt);
                int totalUsed = shorttermTokens + ragTokens + inputTokens + systemTokens;
                int totalBudget = chatModelProvider.chatModel() != null ? 128000 : 8000;
                log.debug("[Orchestrator] Token estimate: shortterm={}, rag={}, input={}, system={}, total={}/{}",
                        shorttermTokens, ragTokens, inputTokens, systemTokens, totalUsed, totalBudget);
                var compressResult = memoryCompressor.compressIfNeeded(
                        sessionId, shorttermMessages, shorttermTokens, totalUsed, totalBudget);
                if (compressResult.type() != MemoryCompressor.CompressionResult.CompressionType.NONE) {
                    compressed = true;
                    log.info("[Memory] Compression triggered: type={}, before={}, after={}",
                            compressResult.type(), compressResult.messagesBefore(), compressResult.messagesAfter());
                    Map<String, String> meta = new HashMap<>(trace.builder().build().metadata());
                    meta.put("compression_type", compressResult.type().name());
                    meta.put("messages_before", String.valueOf(compressResult.messagesBefore()));
                    meta.put("messages_after", String.valueOf(compressResult.messagesAfter()));
                    trace.builder().metadata(meta);
                    // 压缩后重建缓存（从DB加载含摘要的状态）
                    shorttermMessages = messageStore.loadForContext(sessionId);
                    messageCache.put(sessionId, shorttermMessages);
                }

                // 用户消息：异步写DB + 同步更新缓存
                messageWriteWorker.submit(sessionId, "user", text, false);
                messageCache.append(sessionId, new MemoryMessage(0, sessionId, "user", text, false, null));
                sessionStore.updateLastActive(sessionId);
            }

            // ===== Layer 3+4: ReAct loop（AI决策 + 工具执行 + 小压缩去除工具块） =====
            List<ChatMessage> historyChatMessages = convertToChatMessages(shorttermMessages);
            ReActEngine.ReActResult result = reactEngine.execute(systemPrompt, text, historyChatMessages, trace.builder(), null, cancellationToken);
            result.steps().forEach(trace::addStep);

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
            if (sessionId != null) {
                messageWriteWorker.submit(sessionId, "assistant", result.output(), false);
                messageCache.append(sessionId, new MemoryMessage(0, sessionId, "assistant", result.output(), false, null));
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
        long runStart = System.currentTimeMillis();
        int attachCount = attachments != null ? attachments.size() : 0;
        log.info("[Orchestrator] Stream run start: textLen={}, sessionId={}, attachments={}",
                text != null ? text.length() : 0, requestedSessionId, attachCount);

        TraceCollector trace = new TraceCollector(traceStore);

        try {
            // Layer 1: Input
            InputProcessor.InputResult input = inputProcessor.process(token, text, attachments);
            trace.recordInput(input.userId(), text,
                    input.message().attachments().stream().map(AgentMessage.Attachment::name).toList());

            // Layer 1.5: Session lifecycle
            String sessionId = null;
            List<MemoryMessage> shorttermMessages = List.of();
            List<Preference> longtermPrefs = List.of();
            boolean compressed = false;
            if (MemoryStoreFactory.isEnabled() && input.userId() != null) {
                String effectiveSessionId = requestedSessionId != null ? requestedSessionId : activeSessionId;
                SessionLifecycleManager.LifecycleResult lifecycle = sessionLifecycle.process(input.userId(), effectiveSessionId);
                sessionId = lifecycle.session().id();
                activeSessionId = sessionId;
                log.info("[Memory] Session resolved: id={}, isNew={}, timedOut={}",
                        sessionId, lifecycle.isNewSession(), lifecycle.timedOutSessionIds().size());
                trace.builder().sessionId(sessionId);
                Map<String, String> meta = new HashMap<>(trace.builder().build().metadata());
                meta.put("session_id", sessionId);
                meta.put("session_new", String.valueOf(lifecycle.isNewSession()));
                if (!lifecycle.timedOutSessionIds().isEmpty()) {
                    meta.put("sessions_timed_out", String.join(",", lifecycle.timedOutSessionIds()));
                }
                trace.builder().metadata(meta);

                for (String timedOutId : lifecycle.timedOutSessionIds()) {
                    if (sessionLifecycle.isWorthyOfRefinement(timedOutId)) {
                        refinementWorker.submit(timedOutId, input.userId());
                        meta.put("refinement_submitted", timedOutId);
                    }
                }

                shorttermMessages = messageCache.getIfPresent(sessionId);
                if (shorttermMessages == null) {
                    shorttermMessages = messageStore.loadForContext(sessionId);
                    messageCache.put(sessionId, shorttermMessages);
                }

                longtermPrefs = preferenceStore.loadByUser(input.userId());
            }

            // Layer 2: Preprocess
            ContextBuilder.ContextResult ctx = contextBuilder.build(text);
            trace.recordPreprocess(null, ctx.ragHitIds(), null);
            String systemPrompt = buildSystemPrompt(ctx, longtermPrefs, systemPromptOverride);
            trace.recordLlmMeta(chatModelProvider.modelName(), "v1");

            // Compression check
            if (sessionId != null) {
                int shorttermTokens = estimateTokens(shorttermMessages);
                int ragTokens = ctx.contextBlock() != null ? estimateTokens(ctx.contextBlock()) : 0;
                int inputTokens = estimateTokens(text);
                int systemTokens = estimateTokens(systemPrompt);
                int totalUsed = shorttermTokens + ragTokens + inputTokens + systemTokens;
                int totalBudget = chatModelProvider.chatModel() != null ? 128000 : 8000;
                var compressResult = memoryCompressor.compressIfNeeded(
                        sessionId, shorttermMessages, shorttermTokens, totalUsed, totalBudget);
                if (compressResult.type() != MemoryCompressor.CompressionResult.CompressionType.NONE) {
                    compressed = true;
                    log.info("[Memory] Compression triggered: type={}, before={}, after={}",
                            compressResult.type(), compressResult.messagesBefore(), compressResult.messagesAfter());
                    shorttermMessages = messageStore.loadForContext(sessionId);
                    messageCache.put(sessionId, shorttermMessages);
                }

                messageWriteWorker.submit(sessionId, "user", text, false);
                messageCache.append(sessionId, new MemoryMessage(0, sessionId, "user", text, false, null));
            }

            // Emit start event with sessionId (first event for client)
            final String finalSessionId = sessionId;
            callback.onEvent(StreamEvent.start(finalSessionId));

            // Layer 3+4: Streaming ReAct loop
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
                    systemPrompt, text, historyChatMessages, trace.builder(), listener, cancellationToken);
            result.steps().forEach(trace::addStep);

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
            if (finalSessionId != null) {
                messageWriteWorker.submit(finalSessionId, "assistant", result.output(), false);
                messageCache.append(finalSessionId, new MemoryMessage(0, finalSessionId, "assistant", result.output(), false, null));
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
        }
    }

    private String buildSystemPrompt(ContextBuilder.ContextResult ctx, List<Preference> longtermPrefs, String systemPromptOverride) {
        StringBuilder sb = new StringBuilder();
        String basePrompt = (systemPromptOverride != null && !systemPromptOverride.isBlank())
                ? systemPromptOverride
                : EnvConfig.get().getString(EnvKey.SYSTEM_PROMPT,
                        "You are a helpful AI assistant with access to tools. Use tools when needed to answer questions. Think step by step. If a tool fails, try an alternative approach.");
        sb.append(basePrompt).append("\n\n");

        // Inject long-term memory (user preferences), capped at MEMORY_LONGTERM_MAX_TOKENS
        if (!longtermPrefs.isEmpty()) {
            int maxChars = EnvConfig.get().getInt(EnvKey.MEMORY_LONGTERM_MAX_TOKENS, 800) * 3;
            StringBuilder prefBlock = new StringBuilder("[User Preferences]\n");
            for (Preference pref : longtermPrefs) {
                prefBlock.append("- ").append(pref.category()).append(": ").append(pref.content()).append("\n");
            }
            if (prefBlock.length() > maxChars) {
                prefBlock.setLength(maxChars);
                prefBlock.append("...\n");
                log.debug("[Memory] Long-term preferences truncated to {} chars ({} tokens max)", maxChars, maxChars / 3);
            }
            sb.append(prefBlock).append("\n");
        }

        // Inject RAG context
        if (ctx.hasContext()) {
            sb.append(ctx.contextBlock());
        }
        return sb.toString();
    }

    /**
     * Rough token estimate: ~4 chars per token (English), ~2 chars per token (CJK).
     * Uses conservative 3 chars/token as average.
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() / 3;
    }

    private int estimateTokens(List<MemoryMessage> messages) {
        int total = 0;
        for (MemoryMessage msg : messages) {
            total += estimateTokens(msg.content());
        }
        return total;
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
    }

    private void registerMcpTools() {
        List<McpServerConfig> servers = McpServerConfig.loadAll();
        if (servers.isEmpty()) return;

        log.info("Discovering tools from {} MCP server(s)...", servers.size());
        McpToolDiscovery discovery = new McpToolDiscovery();
        discovery.discoverAndRegister(servers, toolRegistry);
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

    /**
     * Convert MemoryMessage list to LangChain4j ChatMessage list for ReAct history injection.
     * Summary rows are converted to AiMessage to preserve compressed context.
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
                // system/tool messages skipped in history for simplicity
            }
        }
        return chatMessages;
    }

    public void shutdown() {
        subAgentOrchestrator.shutdown();
        if (cleanupScheduler != null) cleanupScheduler.stop();
        messageWriteWorker.stop();  // Flush pending writes before stopping
        refinementWorker.stop();
        traceStore.close();
        log.info("Agent shut down");
    }
}
