package com.harness.preprocess.memory;

import com.harness.ai.model.ChatModelProvider;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Memory compression with intelligent extraction and time-decay weighting.
 *
 * Major compression only (tool block stripping is handled by ReActEngine directly):
 *   - Triggered when overall context exceeds HARNESS_CTX_COMPRESS_MAJOR (default 85%)
 *   - Takes all short-term messages
 *   - Intelligent extraction with time decay → compress to HARNESS_CTX_COMPRESS_MAJOR_TARGET (default 30%)
 */
public class MemoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressor.class);

    private final MessageStore messageStore;
    private final SessionStore sessionStore;
    private final ChatModelProvider chatModel;
    private final int majorThreshold;
    private final int majorTargetPercent;

    public MemoryCompressor(MessageStore messageStore, SessionStore sessionStore, ChatModelProvider chatModel) {
        this.messageStore = messageStore;
        this.sessionStore = sessionStore;
        this.chatModel = chatModel;
        EnvConfig cfg = EnvConfig.get();
        this.majorThreshold = cfg.getInt(EnvKey.CTX_COMPRESS_MAJOR, 85);
        this.majorTargetPercent = cfg.getInt(EnvKey.CTX_COMPRESS_MAJOR_TARGET, 30);
    }

    public record CompressionResult(
            CompressionType type,
            int messagesBefore,
            int messagesAfter
    ) {
        public enum CompressionType {
            NONE, MAJOR
        }
    }

    /**
     * Check and apply major compression if needed.
     *
     * @param sessionId       current session
     * @param messages        current short-term messages (loaded before this turn's save)
     * @param shorttermTokens actual tokens used by short-term messages
     * @param totalUsedTokens total tokens used across all parts
     * @param totalBudget     total context window
     * @return compression result
     */
    public CompressionResult compressIfNeeded(
            String sessionId,
            List<MemoryMessage> messages,
            int shorttermTokens,
            int totalUsedTokens,
            int totalBudget) {

        int totalUsagePercent = (int) (totalUsedTokens * 100.0 / totalBudget);

        // Major compression: overall context exceeds threshold
        if (totalUsagePercent >= majorThreshold) {
            log.info("Major compression triggered: totalUsage={}%, threshold={}%", totalUsagePercent, majorThreshold);
            int targetTokens = (int) (totalBudget * majorTargetPercent / 100.0);
            return doMajorCompression(sessionId, messages, targetTokens, totalBudget);
        }

        return new CompressionResult(CompressionResult.CompressionType.NONE, messages.size(), messages.size());
    }

    /**
     * Major compression: summarize all messages with time-decay extraction.
     */
    private CompressionResult doMajorCompression(String sessionId, List<MemoryMessage> messages, int targetTokens, int totalBudget) {
        String summary = generateDecayedSummary(messages, targetTokens, totalBudget);
        messageStore.save(sessionId, "system", List.of(new MessageBlock(MessageBlock.BlockType.TEXT, summary, null)), true);
        sessionStore.updateLastActive(sessionId);

        List<MemoryMessage> freshMessages = messageStore.loadForContext(sessionId);
        log.info("Major compression: summarized {} messages → context now {} messages",
                messages.size(), freshMessages.size());

        return new CompressionResult(CompressionResult.CompressionType.MAJOR, messages.size(), freshMessages.size());
    }

    /**
     * Generate a summary with time-decay weighting.
     * Recent messages get full detail, older messages are progressively condensed.
     */
    private String generateDecayedSummary(List<MemoryMessage> messages, int targetTokens, int totalBudget) {
        ChatModel model = chatModel.chatModel();
        if (model == null) {
            log.warn("Chat model not available for compression, using truncation fallback");
            return fallbackTruncation(messages, targetTokens);
        }

        int total = messages.size();
        StringBuilder conversation = new StringBuilder();
        for (int i = 0; i < total; i++) {
            MemoryMessage msg = messages.get(i);
            String recency = getTimeDecayLabel(i, total);
            conversation.append(String.format("[%s] %s: %s\n", recency, msg.role(), msg.text()));
        }

        int targetChars = targetTokens * 3;
        String prompt = """
                You are a conversation summarizer with time-decay intelligence.

                Rules:
                1. Messages marked [RECENT] — preserve key details, decisions, and facts.
                2. Messages marked [MIDDLE] — keep important facts and user preferences, condense routine exchanges.
                3. Messages marked [OLD] — extract only persistent user preferences, key decisions, and critical facts. Discard conversational filler.
                4. Output a single coherent summary paragraph, NOT a message list.
                5. Target length: ~%d characters (be concise, every word must carry information).
                6. Focus on: user preferences, domain knowledge, open tasks, key decisions, unresolved questions.

                Conversation:
                %s

                Summary (target ~%d chars):
                """.formatted(targetChars, conversation, targetChars);

        try {
            var response = model.chat(UserMessage.from(prompt));
            String summary = response.aiMessage().text();

            // Second pass if still too long
            if (summary.length() > targetChars * 1.5) {
                summary = doSecondPassCompression(model, summary, targetChars);
            }

            return summary;
        } catch (Exception e) {
            log.error("Failed to generate summary: {}", e.getMessage(), e);
            return fallbackTruncation(messages, targetTokens);
        }
    }

    private String doSecondPassCompression(ChatModel model, String summary, int targetChars) {
        String prompt = """
                Compress the following summary to ~%d characters while preserving all key information.
                Remove redundancy, merge similar points, keep only essential facts and preferences.

                Original summary:
                %s

                Compressed summary (~%d chars):
                """.formatted(targetChars, summary, targetChars);
        try {
            var response = model.chat(UserMessage.from(prompt));
            return response.aiMessage().text();
        } catch (Exception e) {
            log.warn("Second-pass compression failed, using first-pass result: {}", e.getMessage());
            return summary.length() > targetChars ? summary.substring(0, targetChars) + "..." : summary;
        }
    }

    private String getTimeDecayLabel(int index, int total) {
        double ratio = (double) index / total;
        if (ratio >= 0.67) return "RECENT";
        if (ratio >= 0.33) return "MIDDLE";
        return "OLD";
    }

    private String fallbackTruncation(List<MemoryMessage> messages, int targetTokens) {
        int targetChars = targetTokens * 3;
        StringBuilder sb = new StringBuilder("[Conversation summary]\n");
        for (int i = messages.size() - 1; i >= 0; i--) {
            MemoryMessage msg = messages.get(i);
            String line = msg.role() + ": " + msg.text() + "\n";
            if (sb.length() + line.length() > targetChars) {
                int remaining = targetChars - sb.length() - 3;
                if (remaining > 0) {
                    sb.insert(0, msg.role() + ": " + msg.text().substring(0, Math.min(remaining, msg.text().length())) + "...\n");
                }
                break;
            }
            sb.insert(0, line);
        }
        return sb.toString();
    }
}
