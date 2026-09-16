package com.harness.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.concurrent.BlockingTaskExecutor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.MessageBlock;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.core.runtime.RunTrace;
import com.harness.input.memory.InMemorySessionMessageCache;
import com.harness.input.memory.MemoryCompressor;
import com.harness.input.memory.MemoryStoreFactory;
import com.harness.input.memory.MessageStore;
import com.harness.input.memory.MessageWriteWorker;
import com.harness.input.memory.SessionLifecycleManager;
import com.harness.input.memory.SessionMessageCache;
import com.harness.input.memory.SessionStore;
import com.harness.input.multimodal.TextChunker;
import com.harness.provider.ChatModelProvider;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.react.ReActResult;
import com.harness.tool.ToolRegistry;
import com.harness.tool.knowledge.authority.KnowledgeRepository;
import com.harness.tool.knowledge.authority.MysqlKnowledgeRepository;
import com.harness.tool.knowledge.authority.MysqlKnowledgeIndexOutboxStore;
import com.harness.tool.knowledge.index.KnowledgeIndexProjector;
import com.harness.tool.knowledge.index.KnowledgeProjectionMapper;
import com.harness.tool.knowledge.index.KnowledgeProjectionStore;
import com.harness.tool.knowledge.index.KnowledgeProjectionStoreFactory;
import com.harness.tool.knowledge.index.KnowledgeReindexService;
import com.harness.tool.skill.SkillRegistry;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.Clock;

/** Owns session memory, compression, persistence, and their background workers. */
public final class AgentMemoryRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryRuntime.class);

    /**
     * Per-session single-writer guards, striped by session hash.
     *
     * <p>One continuation — a run or a sub-agent resume — must write a session's transcript from
     * its first message to its last before another one starts. The cache backends make each
     * individual append atomic ({@code RPUSH} under a Lua existence check); this makes the
     * continuation atomic. Without it two writers interleave and the history reads back as an
     * assistant tool call whose results are separated by the other writer's messages, which
     * every OpenAI-compatible provider rejects.
     *
     * <p>Static and striped on purpose: the transcript it guards is process-global, and a fixed
     * stripe count bounds the pool without eviction — a collision only serializes two unrelated
     * sessions, it never corrupts one.
     */
    private static final int SESSION_WRITE_STRIPES = 64;
    private static final Object[] SESSION_WRITE_LOCKS = newSessionWriteLocks();

    private static Object[] newSessionWriteLocks() {
        Object[] stripes = new Object[SESSION_WRITE_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private final boolean enabled;
    private final ChatModelProvider chatModel;
    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final SessionLifecycleManager sessionLifecycle;
    private final MemoryCompressor memoryCompressor;
    private final SessionCleanupScheduler cleanupScheduler;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeProjectionStore knowledgeProjectionStore;
    private final KnowledgeIndexOutboxWorker indexOutboxWorker;
    private final KnowledgeReindexService knowledgeReindexService;
    private final SessionMessageCache messageCache;
    private final SessionContextLoader sessionContextLoader;
    private final MessageWriteWorker messageWriteWorker;

    public AgentMemoryRuntime(
            ChatModelProvider chatModel,
            EmbeddingModelProvider embeddingModel,
            SkillRegistry skillRegistry,
            ToolRegistry toolRegistry,
            com.harness.tool.rag.VectorStore vectorStore
    ) {
        this.enabled = MemoryStoreFactory.isEnabled();
        this.chatModel = chatModel;
        if (enabled) {
            this.sessionStore = MemoryStoreFactory.createSessionStore();
            this.messageStore = MemoryStoreFactory.createMessageStore();
            this.sessionLifecycle = new SessionLifecycleManager(sessionStore);
            this.memoryCompressor = new MemoryCompressor(messageStore, sessionStore, chatModel);
            this.messageCache = MemoryStoreFactory.createMessageCache();
            this.messageWriteWorker = new MessageWriteWorker(messageStore);
            this.cleanupScheduler = new SessionCleanupScheduler(messageCache, skillRegistry);
            ObjectMapper objectMapper = new ObjectMapper();
            Clock clock = Clock.systemUTC();
            var projectionRuntime = KnowledgeProjectionStoreFactory.create(embeddingModel);
            this.knowledgeProjectionStore = projectionRuntime.store();
            this.knowledgeRepository = new MysqlKnowledgeRepository(
                    com.harness.core.env.MysqlConnectionPool::getConnection, objectMapper,
                    knowledgeProjectionStore, vectorStore);
            if (projectionRuntime.enabled()) {
                KnowledgeIndexProjector projector = new KnowledgeIndexProjector(
                        knowledgeRepository,
                        projectionRuntime.store(),
                        new KnowledgeProjectionMapper(embeddingModel));
                this.indexOutboxWorker = new KnowledgeIndexOutboxWorker(
                        new MysqlKnowledgeIndexOutboxStore(),
                        projector,
                        clock,
                        KnowledgeIndexOutboxSettings.fromEnvironment());
                this.knowledgeReindexService = new KnowledgeReindexService(
                        knowledgeRepository,
                        projectionRuntime.store(),
                        new KnowledgeProjectionMapper(embeddingModel));
            } else {
                this.indexOutboxWorker = null;
                this.knowledgeReindexService = null;
            }

            messageCache.setOnEvict(skillRegistry::clearSession);
            messageWriteWorker.start();
            if (indexOutboxWorker != null) {
                indexOutboxWorker.start();
            }
            cleanupScheduler.start();
        } else {
            this.sessionStore = null;
            this.messageStore = null;
            this.sessionLifecycle = null;
            this.memoryCompressor = null;
            this.cleanupScheduler = null;
            this.knowledgeRepository = null;
            this.knowledgeProjectionStore = null;
            this.indexOutboxWorker = null;
            this.knowledgeReindexService = null;
            this.messageCache = new InMemorySessionMessageCache();
            this.messageWriteWorker = null;
            messageCache.setOnEvict(skillRegistry::clearSession);
        }
        this.sessionContextLoader = new SessionContextLoader(messageCache, messageStore);
    }

    public void signalKnowledgeIndex() {
        if (indexOutboxWorker != null) indexOutboxWorker.signal();
    }

    public boolean enabled() {
        return enabled;
    }

    public MemoryContext resolve(
            String userId,
            String tenantId,
            String requestedSessionId,
            String text,
            RunTrace trace
    ) {
        if (!enabled || userId == null) {
            String sessionId = requestedSessionId != null
                    ? requestedSessionId
                    : UUID.randomUUID().toString();
            return new MemoryContext(sessionId, userId, tenantId, List.of());
        }

        SessionLifecycleManager.LifecycleResult lifecycle =
                sessionLifecycle.process(userId, tenantId, requestedSessionId);
        String sessionId = lifecycle.session().id();
        trace.setSessionId(sessionId);
        Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
        metadata.put("session_id", sessionId);
        metadata.put("session_new", String.valueOf(lifecycle.isNewSession()));
        if (!lifecycle.timedOutSessionIds().isEmpty()) {
            metadata.put("sessions_timed_out", String.join(",", lifecycle.timedOutSessionIds()));
        }
        trace.putMetadata(metadata);

        if (lifecycle.isNewSession()) {
            String safeText = text == null ? "" : text;
            sessionStore.updateTitle(
                    sessionId,
                    safeText.length() > 100 ? safeText.substring(0, 100) : safeText);
        }
        CompletableFuture<List<MemoryMessage>> shorttermFuture =
                CompletableFuture.supplyAsync(
                        () -> loadMessages(sessionId, userId, trace),
                        BlockingTaskExecutor.shared());
        return new MemoryContext(
                sessionId,
                userId,
                lifecycle.session().tenantId(),
                shorttermFuture.join());
    }

    public CompressionOutcome compress(
            String sessionId,
            String userId,
            List<MemoryMessage> shorttermMessages,
            String userMessage,
            String systemPrompt
    ) {
        if (!enabled) {
            return new CompressionOutcome(null, shorttermMessages);
        }
        int totalBudget = chatModel.chatModel() != null ? chatModel.contextWindow() : 8000;
        int shorttermTokens = estimateTokens(shorttermMessages);
        int totalUsed = shorttermTokens + TextChunker.estimateTokens(userMessage)
                + TextChunker.estimateTokens(systemPrompt);
        int majorThreshold = EnvConfig.get().getInt(EnvKey.CTX_COMPRESS_MAJOR, 85);
        int usagePercent = (int) (totalUsed * 100.0 / totalBudget);
        if (usagePercent >= majorThreshold) {
            messageWriteWorker.flushPending();
            shorttermMessages = messageStore.loadForContext(sessionId);
            shorttermTokens = estimateTokens(shorttermMessages);
            totalUsed = shorttermTokens + TextChunker.estimateTokens(userMessage)
                    + TextChunker.estimateTokens(systemPrompt);
            usagePercent = (int) (totalUsed * 100.0 / totalBudget);
        }

        MemoryCompressor.CompressionResult majorResult =
                new MemoryCompressor.CompressionResult(
                        MemoryCompressor.CompressionResult.CompressionType.NONE, 0, 0);
        if (usagePercent >= majorThreshold - 10) {
            majorResult = memoryCompressor.compressIfNeeded(
                    sessionId, shorttermMessages, shorttermTokens, totalUsed, totalBudget);
        }
        if (majorResult.type() != MemoryCompressor.CompressionResult.CompressionType.NONE) {
            shorttermMessages = messageStore.loadForContext(sessionId);
            messageCache.put(sessionId, userId, shorttermMessages);
        }
        return new CompressionOutcome(majorResult, shorttermMessages);
    }

    public void recordCompressionMetadata(RunTrace trace, CompressionOutcome outcome) {
        if (!outcome.hasMajor()) {
            return;
        }
        Map<String, String> metadata = new HashMap<>(trace.snapshot().metadata());
        metadata.put("compression_type", outcome.majorResult().type().name());
        metadata.put("messages_before", String.valueOf(outcome.majorResult().messagesBefore()));
        metadata.put("messages_after", String.valueOf(outcome.majorResult().messagesAfter()));
        trace.putMetadata(metadata);
    }

    public void persistUserMessage(
            String sessionId,
            String userId,
            String rootTraceId,
            String text,
            boolean updateActivity
    ) {
        if (!enabled || sessionId == null || userId == null) {
            return;
        }
        List<MessageBlock> blocks = List.of(
                new MessageBlock(MessageBlock.BlockType.TEXT, text, null));
        messageWriteWorker.submit(sessionId, rootTraceId, "user", blocks, false);
        messageCache.appendIfPresent(
                sessionId,
                userId,
                new MemoryMessage(0, sessionId, rootTraceId, "user", blocks, false, null));
        if (updateActivity) {
            sessionStore.updateLastActive(sessionId);
        }
    }

    public void persistAssistantMessage(
            String sessionId,
            String userId,
            String rootTraceId,
            List<MessageBlock> blocks,
            boolean updateActivity
    ) {
        persistAssistantMessage(
                sessionId, userId, rootTraceId, blocks, updateActivity, true);
    }

    public void persistAssistantMessage(
            String sessionId,
            String userId,
            String rootTraceId,
            List<MessageBlock> blocks,
            boolean updateActivity,
            boolean completesTurn
    ) {
        if (!enabled || sessionId == null || userId == null) {
            return;
        }
        String role = completesTurn ? "assistant" : "assistant_partial";
        messageWriteWorker.submit(sessionId, rootTraceId, role, blocks, false);
        messageCache.appendIfPresent(
                sessionId,
                userId,
                new MemoryMessage(0, sessionId, rootTraceId, role, blocks, false, null));
        if (updateActivity) {
            sessionStore.updateLastActive(sessionId);
        }
    }

    public void persistToolMessages(
            ReActResult result,
            String sessionId,
            String userId,
            String rootTraceId
    ) {
        if (sessionId == null || userId == null) {
            return;
        }
        for (ReActStep step : result.steps()) {
            if (step.toolCalls() == null || step.toolCalls().isEmpty()) {
                continue;
            }
            appendContextMessage(
                    sessionId,
                    userId,
                    rootTraceId,
                    ToolMemoryCodec.TOOL_CALL_ROLE,
                    ToolMemoryCodec.encodeCalls(step.toolCalls()));
            if (step.toolResults() == null) {
                continue;
            }
            for (ToolResult toolResult : step.toolResults()) {
                appendContextMessage(
                        sessionId,
                        userId,
                        rootTraceId,
                        ToolMemoryCodec.TOOL_RESULT_ROLE,
                        ToolMemoryCodec.encodeResult(toolResult));
            }
        }
    }

    public void persistSubAgentEvents(
            String sessionId,
            String userId,
            String turnId,
            List<com.harness.agent.SessionInbox.SubAgentCompletedEvent> events
    ) {
        for (var event : events) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("toolName", "spawn_subagent");
            metadata.put("taskId", event.taskId());
            metadata.put("status", event.result().status().name());
            if (event.result().error() != null) {
                metadata.put("error", event.result().error());
            }
            List<MessageBlock> blocks = new java.util.ArrayList<>();
            blocks.add(new MessageBlock(
                    MessageBlock.BlockType.TEXT,
                    event.result().output() != null
                            ? event.result().output()
                            : "ERROR: " + event.result().error(),
                    null,
                    Map.copyOf(metadata)));
            event.result().artifacts().forEach(artifact -> blocks.add(new MessageBlock(
                    MessageBlock.BlockType.ARTIFACT,
                    null,
                    artifact.id(),
                    Map.of(
                            "type", artifact.type().name(),
                            "mimeType", artifact.mimeType() != null ? artifact.mimeType() : "",
                            "name", artifact.name() != null ? artifact.name() : ""))));
            appendContextMessage(
                    sessionId, userId, turnId, "subagent_event", List.copyOf(blocks));
        }
    }

    public List<ChatMessage> toChatMessages(List<MemoryMessage> memoryMessages) {
        return ToolMemoryCodec.toChatMessages(memoryMessages);
    }

    public List<MemoryMessage> loadMessages(String sessionId, String userId) {
        return loadMessages(sessionId, userId, RunTrace.noop());
    }

    private List<MemoryMessage> loadMessages(String sessionId, String userId, RunTrace trace) {
        return sessionContextLoader.load(sessionId, userId, trace);
    }

    public String findSessionUserId(String sessionId) {
        if (!enabled) {
            return null;
        }
        return sessionStore.findByIdForInternalTask(sessionId)
                .map(session -> session.userId())
                .orElse(null);
    }

    public void updateActivity(String sessionId) {
        if (enabled && sessionId != null) {
            sessionStore.updateLastActive(sessionId);
        }
    }

    public void updateActivityAsync(String sessionId) {
        if (!enabled || sessionId == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                sessionStore.updateLastActive(sessionId);
            } catch (Exception e) {
                log.error("Async updateLastActive failed: {}", e.getMessage(), e);
            }
        }, BlockingTaskExecutor.shared());
    }

    public SessionStore sessionStore() {
        return sessionStore;
    }

    public MessageStore messageStore() {
        return messageStore;
    }

    public SessionLifecycleManager sessionLifecycle() {
        return sessionLifecycle;
    }

    public SessionMessageCache messageCache() {
        return messageCache;
    }

    public MessageWriteWorker messageWriteWorker() {
        return messageWriteWorker;
    }

    public KnowledgeReindexService knowledgeReindexService() {
        return knowledgeReindexService;
    }

    public KnowledgeRepository knowledgeRepository() {
        if (knowledgeRepository == null) {
            throw new IllegalStateException("Knowledge authority requires HARNESS_MEMORY_STORE=mysql");
        }
        return knowledgeRepository;
    }

    public KnowledgeProjectionStore knowledgeProjectionStore() {
        if (knowledgeProjectionStore == null) {
            throw new IllegalStateException(
                    "Knowledge projection search requires MySQL memory and an enabled RAG provider");
        }
        return knowledgeProjectionStore;
    }

    public void awaitMessageWrites(String rootTraceId) {
        if (enabled) {
            messageWriteWorker.awaitTrace(rootTraceId);
        }
    }

    /**
     * Runs {@code action} holding this session's single-writer guard. Callers wrap a whole
     * continuation tail — persist the tool round, persist the assistant message, await the
     * writes — so the next continuation only enters once the transcript is stable at the store,
     * not merely at the cache.
     *
     * <p>ponytail: guards the write tail, not the run. A continuation that loaded history before
     * another writer committed still builds its prompt from a stale tail. Widen the guard to the
     * whole ReAct loop if that ever surfaces.
     */
    public static void withSessionWriteLock(String sessionId, Runnable action) {
        Object lock = SESSION_WRITE_LOCKS[
                Math.floorMod(sessionId.hashCode(), SESSION_WRITE_STRIPES)];
        synchronized (lock) {
            action.run();
        }
    }

    public void shutdown() {
        if (indexOutboxWorker != null) {
            indexOutboxWorker.stop();
        }
        if (cleanupScheduler != null) {
            cleanupScheduler.stop();
        }
        if (messageWriteWorker != null) {
            messageWriteWorker.stop();
        }
        messageCache.evictExpired();
    }

    private void appendContextMessage(
            String sessionId,
            String userId,
            String rootTraceId,
            String role,
            List<MessageBlock> blocks
    ) {
        if (messageWriteWorker != null) {
            messageWriteWorker.submit(sessionId, rootTraceId, role, blocks, false);
        }
        messageCache.appendIfPresent(
                sessionId,
                userId,
                new MemoryMessage(0, sessionId, rootTraceId, role, blocks, false, null));
    }

    private static int estimateTokens(List<MemoryMessage> messages) {
        return messages.stream()
                .mapToInt(message -> TextChunker.estimateTokens(message.modelText()))
                .sum();
    }

    public record MemoryContext(
            String sessionId,
            String userId,
            String tenantId,
            List<MemoryMessage> shorttermMessages
    ) {
    }

    public record CompressionOutcome(
            MemoryCompressor.CompressionResult majorResult,
            List<MemoryMessage> finalMessages
    ) {
        public boolean hasMajor() {
            return majorResult != null
                    && majorResult.type()
                    != MemoryCompressor.CompressionResult.CompressionType.NONE;
        }
    }
}
