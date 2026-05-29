package com.harness.preprocess.memory;

import com.harness.ai.model.ChatModelProvider;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.Preference;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
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
        log.info("Processing refinement for session {}, user {}", task.sessionId(), task.userId());

        // Load session messages
        List<MemoryMessage> messages = messageStore.loadForContext(task.sessionId());
        if (messages.isEmpty()) {
            log.debug("No messages found for session {}, skipping", task.sessionId());
            return;
        }

        // Load existing preferences
        List<Preference> existingPrefs = preferenceStore.loadByUser(task.userId());

        // Generate refinement
        String refined = generateRefinement(messages, existingPrefs);
        if (refined == null || refined.isBlank()) {
            log.debug("No refinement generated for session {}", task.sessionId());
            return;
        }

        // Parse and upsert preferences
        parseAndUpsertPrefs(task.userId(), task.sessionId(), refined);
    }

    private String generateRefinement(List<MemoryMessage> messages, List<Preference> existingPrefs) {
        ChatModel model = chatModel.chatModel();
        if (model == null) {
            log.warn("Chat model not available for preference refinement");
            return null;
        }

        StringBuilder conversation = new StringBuilder();
        for (MemoryMessage msg : messages) {
            if (!msg.isSummary()) {
                conversation.append(msg.role()).append(": ").append(msg.content()).append("\n");
            }
        }

        StringBuilder existing = new StringBuilder();
        for (Preference pref : existingPrefs) {
            existing.append("- ").append(pref.category()).append(": ").append(pref.content()).append("\n");
        }

        int maxChars = longtermMaxTokens * 3;
        String prompt = """
                You are a user preference extraction system. Analyze the conversation and extract user preferences.

                Existing preferences:
                %s

                Conversation:
                %s

                Instructions:
                1. Merge new observations with existing preferences
                2. Resolve contradictions (newer information takes priority)
                3. Deduplicate similar preferences
                4. Categorize each preference (language, tone, domain, workflow, other)
                5. Output format: one preference per line as "category: description"
                6. If no meaningful preferences found, output "NONE"
                7. CRITICAL: Total output must be under %d characters (~%d tokens). Be concise — every word must carry information. Omit low-value preferences if needed.
                """.formatted(
                existing.isEmpty() ? "(none)" : existing.toString(),
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

    private void parseAndUpsertPrefs(String userId, String sessionId, String refined) {
        if (refined.strip().equalsIgnoreCase("NONE")) {
            return;
        }

        String[] lines = refined.split("\n");
        for (String line : lines) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#") || line.equalsIgnoreCase("NONE")) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0) continue;

            String category = line.substring(0, colonIdx).strip().toLowerCase();
            String content = line.substring(colonIdx + 1).strip();

            if (!content.isEmpty()) {
                preferenceStore.upsert(userId, category, content, sessionId);
                log.debug("Upserted preference: {} = {}", category, content);
            }
        }
    }
}
