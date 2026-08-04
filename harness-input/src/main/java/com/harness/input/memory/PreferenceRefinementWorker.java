package com.harness.input.memory;

import com.harness.provider.ChatModelProvider;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.Preference;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background worker that refines long-term user preferences from completed sessions.
 * Uses an in-memory queue + single background thread (lightweight).
 *
 * Flow:
 * 1. Receive sessionId + userId
 * 2. Load session messages
 * 3. Load existing preferences
 * 4. Call main model to merge/refine
 * 5. Upsert preferences by category
 */
public class PreferenceRefinementWorker {

    private static final Logger log = LoggerFactory.getLogger(PreferenceRefinementWorker.class);

    private final MessageStore messageStore;
    private final PreferenceStore preferenceStore;
    private final ChatModelProvider chatModel;
    private final int longtermMaxTokens;
    private final BlockingQueue<RefinementTask> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public PreferenceRefinementWorker(MessageStore messageStore, PreferenceStore preferenceStore, ChatModelProvider chatModel) {
        this.messageStore = messageStore;
        this.preferenceStore = preferenceStore;
        this.chatModel = chatModel;
        this.longtermMaxTokens = EnvConfig.get().getInt(EnvKey.MEMORY_LONGTERM_MAX_TOKENS, 800);
    }

    /**
     * A refinement task: refine preferences for a user based on a completed session.
     */
    public record RefinementTask(String sessionId, String userId) {}

    /**
     * Start the background worker thread.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::run, "pref-refinement-worker");
            workerThread.setDaemon(true);
            workerThread.start();
            log.info("Preference refinement worker started");
        }
    }

    /**
     * Stop the background worker thread.
     */
    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("Preference refinement worker stopped");
    }

    /**
     * Submit a session for preference refinement (non-blocking).
     */
    public void submit(String sessionId, String userId) {
        queue.offer(new RefinementTask(sessionId, userId));
        log.debug("Submitted refinement task for session {}, user {}", sessionId, userId);
    }

    private void run() {
        while (running.get()) {
            try {
                RefinementTask task = queue.take();
                processTask(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in refinement worker: {}", e.getMessage(), e);
            }
        }
    }

    private void processTask(RefinementTask task) {
        log.debug("Processing refinement for session {}, user {}", task.sessionId(), task.userId());

        // Load session messages
        List<MemoryMessage> messages = messageStore.loadForContext(task.sessionId());
        if (messages.isEmpty()) {
            log.debug("No messages found for session {}, skipping", task.sessionId());
            return;
        }

        // Load existing memory (single record)
        List<Preference> existingPrefs = preferenceStore.loadByUser(task.userId());
        String existingMemory = existingPrefs.isEmpty() ? "" : existingPrefs.get(0).content();

        // Generate updated memory
        String updatedMemory = generateRefinement(messages, existingMemory);
        if (updatedMemory == null || updatedMemory.isBlank()) {
            log.debug("No refinement generated for session {}", task.sessionId());
            return;
        }

        // Upsert as single "memory" record
        preferenceStore.upsert(task.userId(), "memory", updatedMemory.trim(), task.sessionId());
        log.info("Updated long-term memory for user {} from session {} ({} chars)",
                task.userId(), task.sessionId(), updatedMemory.trim().length());
    }

    private String generateRefinement(List<MemoryMessage> messages, String existingMemory) {
        ChatModel model = chatModel.chatModel();
        if (model == null) {
            log.warn("Chat model not available for preference refinement");
            return null;
        }

        StringBuilder conversation = new StringBuilder();
        for (MemoryMessage msg : messages) {
            if (!msg.isSummary()) {
                conversation.append(msg.role()).append(": ").append(msg.text()).append("\n");
            }
        }

        int maxChars = longtermMaxTokens * 3;
        String prompt = """
                You are a user memory extraction system. Analyze the conversation and update the user's memory profile.

                Existing memory:
                %s

                Conversation:
                %s

                Instructions:
                1. Merge new observations with the existing memory
                2. Resolve contradictions (newer information takes priority)
                3. Include: identity, preferences, habits, communication style, expertise, ongoing projects, relationships, goals
                4. Remove outdated or contradicted information
                5. Output a SINGLE consolidated memory text (not multiple lines/categories)
                6. If no meaningful information found, output "NONE"
                7. CRITICAL: Total output must be under %d characters (~%d tokens). Be concise — every word must carry information.
                """.formatted(
                existingMemory.isEmpty() ? "(none)" : existingMemory,
                conversation.toString(),
                maxChars, longtermMaxTokens
        );

        try {
            var response = model.chat(UserMessage.from(prompt));
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("Failed to generate preference refinement: {}", e.getMessage(), e);
            return null;
        }
    }

}
