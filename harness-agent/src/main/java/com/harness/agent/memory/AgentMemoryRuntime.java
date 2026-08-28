package com.harness.agent.memory;

import com.harness.core.concurrent.BlockingTaskExecutor;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.MessageBlock;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.Preference;
import com.harness.core.model.ReActStep;
import com.harness.core.model.ToolResult;
import com.harness.core.runtime.RunTrace;
import com.harness.input.memory.InMemorySessionMessageCache;
import com.harness.input.memory.MemoryCompressor;
import com.harness.input.memory.MemoryStoreFactory;
import com.harness.input.memory.MessageStore;
import com.harness.input.memory.MessageWriteWorker;
import com.harness.input.memory.PreferenceRefinementWorker;
import com.harness.input.memory.PreferenceStore;
import com.harness.input.memory.SessionLifecycleManager;
import com.harness.input.memory.SessionMessageCache;
import com.harness.input.memory.SessionStore;
import com.harness.input.multimodal.TextChunker;
import com.harness.provider.ChatModelProvider;
import com.harness.react.ReActResult;
import com.harness.tool.ToolRegistry;
import com.harness.tool.builtin.UpdateMemoryTool;
import com.harness.tool.skill.SkillRegistry;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Owns session memory, compression, persistence, and their background workers. */
public final class AgentMemoryRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryRuntime.class);

    private final boolean enabled;
    private final ChatModelProvider chatModel;
    private final SessionStore sessionStore;
    private final MessageStore messageStore;
    private final PreferenceStore preferenceStore;
    private final SessionLifecycleManager sessionLifecycle;
    private final PreferenceRefinementWorker refinementWorker;
    private final MemoryCompressor memoryCompressor;
    private final SessionCleanupScheduler cleanupScheduler;
    private final SessionMessageCache messageCache;
    private final SessionContextLoader sessionContextLoader;
    private final MessageWriteWorker messageWriteWorker;

    public AgentMemoryRuntime(
            ChatModelProvider chatModel,
            SkillRegistry skillRegistry,
            ToolRegistry toolRegistry
    ) {
        this.enabled = MemoryStoreFactory.isEnabled();
        this.chatModel = chatModel;
        if (enabled) {
            this.sessionStore = MemoryStoreFactory.createSessionStore();
            this.messageStore = MemoryStoreFactory.createMessageStore();
            this.preferenceStore = MemoryStoreFactory.createPreferenceStore();
            this.sessionLifecycle = new SessionLifecycleManager(sessionStore, messageStore);
            this.refinementWorker = new PreferenceRefinementWorker(
                    messageStore, preferenceStore, chatModel);
            this.memoryCompressor = new MemoryCompressor(messageStore, sessionStore, chatModel);
            this.messageCache = MemoryStoreFactory.createMessageCache();
            this.messageWriteWorker = new MessageWriteWorker(messageStore);
            this.cleanupScheduler = new SessionCleanupScheduler(
                    sessionStore,
                    sessionLifecycle,
                    refinementWorker,
                    messageCache,
                    skillRegistry);

            messageCache.setOnEvict(skillRegistry::clearSession);
            refinementWorker.start();
            messageWriteWorker.start();
            cleanupScheduler.start();
            registerUpdateMemoryTool(toolRegistry);
        } else {
            this.sessionStore = null;
            this.messageStore = null;
            this.preferenceStore = null;
            this.sessionLifecycle = null;
            this.refinementWorker = null;
            this.memoryCompressor = null;
            this.cleanupScheduler = null;
            this.messageCache = new InMemorySessionMessageCache();
            this.messageWriteWorker = null;
            messageCache.setOnEvict(skillRegistry::clearSession);
        }
        this.sessionContextLoader = new SessionContextLoader(messageCache, messageStore);
    }

    public boolean enabled() {
        return enabled;
    }

    public MemoryContext resolve(
            String userId,
            String requestedSessionId,
            String text,
            RunTrace trace
    ) {
        if (!enabled || userId == null) {
            String sessionId = requestedSessionId != null
                    ? requestedSessionId
                    : UUID.randomUUID().toString();
            return new MemoryContext(sessionId, userId, List.of(), List.of());
        }

        SessionLifecycleManager.LifecycleResult lifecycle =
                sessionLifecycle.process(userId, requestedSessionId);
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
        scheduleRefinement(lifecycle.timedOutSessionIds(), userId);

        CompletableFuture<List<MemoryMessage>> shorttermFuture =
                CompletableFuture.supplyAsync(
                        () -> loadMessages(sessionId, userId, trace),
                        BlockingTaskExecutor.shared());
        CompletableFuture<List<Preference>> longtermFuture =
                CompletableFuture.supplyAsync(
                        () -> preferenceStore.loadByUser(userId),
                        BlockingTaskExecutor.shared());
        CompletableFuture.allOf(shorttermFuture, longtermFuture).join();
        return new MemoryContext(
                sessionId,
                userId,
                shorttermFuture.join(),
                longtermFuture.join());
    }

    public CompressionOutcome compress(
            String sessionId,
            String userId,
            List<MemoryMessage> shorttermMessages,
            String userMessage,
            String systemPrompt
    ) {
        if (!enabled) {
            return new CompressionOutcome(0, null, shorttermMessages);
        }
        int totalBudget = chatModel.chatModel() != null ? chatModel.contextWindow() : 8000;
        int shorttermTokens = estimateTokens(shorttermMessages);
        int totalUsed = shorttermTokens + TextChunker.estimateTokens(userMessage)
                + TextChunker.estimateTokens(systemPrompt);
        int majorThreshold = EnvConfig.get().getInt(EnvKey.CTX_COMPRESS_MAJOR, 85);
        int usagePercent = (int) (totalUsed * 100.0 / totalBudget);
        int minorStripped = 0;

        if (usagePercent >= majorThreshold) {
            minorStripped = stripToolMessages(sessionId, userId);
            if (minorStripped > 0) {
                shorttermMessages = Optional.ofNullable(messageCache.getIfPresent(sessionId))
                        .orElseGet(() -> messageStore.loadForContext(sessionId));
                shorttermTokens = estimateTokens(shorttermMessages);
                totalUsed = shorttermTokens + TextChunker.estimateTokens(userMessage)
                        + TextChunker.estimateTokens(systemPrompt);
                usagePercent = (int) (totalUsed * 100.0 / totalBudget);
            }
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
        return new CompressionOutcome(minorStripped, majorResult, shorttermMessages);
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
            String text,
            boolean updateActivity
    ) {
        if (!enabled || sessionId == null || userId == null) {
            return;
        }
        List<MessageBlock> blocks = List.of(
                new MessageBlock(MessageBlock.BlockType.TEXT, text, null));
        messageWriteWorker.submit(sessionId, "user", blocks, false);
        messageCache.append(
                sessionId,
                userId,
                new MemoryMessage(0, sessionId, "user", blocks, false, null));
        if (updateActivity) {
            sessionStore.updateLastActive(sessionId);
        }
    }

    public void persistAssistantMessage(
            String sessionId,
            String userId,
            List<MessageBlock> blocks,
            boolean updateActivity
    ) {
        if (!enabled || sessionId == null || userId == null) {
            return;
        }
        messageWriteWorker.submit(sessionId, "assistant", blocks, false);
        messageCache.append(
                sessionId,
                userId,
                new MemoryMessage(0, sessionId, "assistant", blocks, false, null));
        if (updateActivity) {
            sessionStore.updateLastActive(sessionId);
        }
    }

    public void persistToolMessages(ReActResult result, String sessionId, String userId) {
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
                    ToolMemoryCodec.TOOL_CALL_ROLE,
                    ToolMemoryCodec.encodeCalls(step.toolCalls()));
            if (step.toolResults() == null) {
                continue;
            }
            for (ToolResult toolResult : step.toolResults()) {
                appendContextMessage(
                        sessionId,
                        userId,
                        ToolMemoryCodec.TOOL_RESULT_ROLE,
                        ToolMemoryCodec.encodeResult(toolResult));
            }
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

    public List<Preference> loadPreferences(String userId) {
        return enabled && userId != null ? preferenceStore.loadByUser(userId) : List.of();
    }

    public String findSessionUserId(String sessionId) {
        if (!enabled) {
            return null;
        }
        return sessionStore.findById(sessionId).map(session -> session.userId()).orElse(null);
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

    public PreferenceStore preferenceStore() {
        return preferenceStore;
    }

    public SessionLifecycleManager sessionLifecycle() {
        return sessionLifecycle;
    }

    public PreferenceRefinementWorker refinementWorker() {
        return refinementWorker;
    }

    public SessionMessageCache messageCache() {
        return messageCache;
    }

    public MessageWriteWorker messageWriteWorker() {
        return messageWriteWorker;
    }

    public void shutdown() {
        if (cleanupScheduler != null) {
            cleanupScheduler.stop();
        }
        if (messageWriteWorker != null) {
            messageWriteWorker.stop();
        }
        if (refinementWorker != null) {
            refinementWorker.stop();
        }
        messageCache.evictExpired();
    }

    private void registerUpdateMemoryTool(ToolRegistry toolRegistry) {
        toolRegistry.register(new UpdateMemoryTool((userId, content, sessionId) -> {
            String existing = preferenceStore.loadByUser(userId).stream()
                    .filter(preference -> "memory".equals(preference.category()))
                    .findFirst()
                    .map(Preference::content)
                    .orElse("");
            String merged = existing.isEmpty() ? content : existing + " # " + content;
            preferenceStore.upsert(userId, "memory", merged, sessionId);
        }));
    }

    private void scheduleRefinement(List<String> timedOutSessionIds, String userId) {
        for (String timedOutSessionId : timedOutSessionIds) {
            CompletableFuture.runAsync(() -> {
                if (sessionLifecycle.isWorthyOfRefinement(timedOutSessionId)) {
                    refinementWorker.submit(timedOutSessionId, userId);
                }
            }, BlockingTaskExecutor.shared());
        }
    }

    private void appendContextMessage(
            String sessionId,
            String userId,
            String role,
            List<MessageBlock> blocks
    ) {
        if (messageWriteWorker != null) {
            messageWriteWorker.submit(sessionId, role, blocks, false);
        }
        messageCache.append(
                sessionId,
                userId,
                new MemoryMessage(0, sessionId, role, blocks, false, null));
    }

    private int stripToolMessages(String sessionId, String userId) {
        if (messageWriteWorker != null) {
            messageWriteWorker.flushPending();
        }
        int persistedRemoved = messageStore == null
                ? 0
                : messageStore.deleteToolMessages(sessionId);
        List<MemoryMessage> cached = messageCache.getIfPresent(sessionId);
        if (cached == null || cached.isEmpty()) {
            return persistedRemoved;
        }
        List<MemoryMessage> stripped = cached.stream()
                .filter(message -> !ToolMemoryCodec.TOOL_RESULT_ROLE.equals(message.role()))
                .filter(message -> !ToolMemoryCodec.TOOL_CALL_ROLE.equals(message.role()))
                .filter(message -> !("assistant".equals(message.role())
                        && message.text().startsWith("[Tool call]")))
                .toList();
        int removed = cached.size() - stripped.size();
        if (removed > 0) {
            messageCache.put(sessionId, userId, stripped);
        }
        return Math.max(removed, persistedRemoved);
    }

    private static int estimateTokens(List<MemoryMessage> messages) {
        return messages.stream()
                .mapToInt(message -> TextChunker.estimateTokens(message.modelText()))
                .sum();
    }

    public record MemoryContext(
            String sessionId,
            String userId,
            List<MemoryMessage> shorttermMessages,
            List<Preference> longtermPreferences
    ) {
    }

    public record CompressionOutcome(
            int minorStripped,
            MemoryCompressor.CompressionResult majorResult,
            List<MemoryMessage> finalMessages
    ) {
        public boolean hasMinor() {
            return minorStripped > 0;
        }

        public boolean hasMajor() {
            return majorResult != null
                    && majorResult.type()
                    != MemoryCompressor.CompressionResult.CompressionType.NONE;
        }
    }
}
