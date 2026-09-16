package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;

import java.util.List;
import java.util.Optional;

/**
 * Persistence interface for conversation messages within a session.
 */
public interface MessageStore {
    /**
     * Save a message with structured content blocks.
     */
    long save(MessageWrite message);

    List<Long> saveBatch(List<MessageWrite> messages);

    List<MemoryMessage> loadForContext(String sessionId);

    /** Resolve one exact message only when it belongs to the supplied session. */
    Optional<MemoryMessage> findByIdAndSession(long messageId, String sessionId);

    int countUserMessages(String sessionId);
    int sumUserContentLength(String sessionId);

    /**
     * Count conversation turns (user+assistant message pairs) in a session.
     */
    int countConversationTurns(String sessionId);

    ConversationBoundary findConversationBoundary(String sessionId);

    /**
     * Count messages with tool-related roles (e.g., tool execution results).
     */
    int countToolMessages(String sessionId);

    /**
     * Calculate average AI reply length (characters) in a session.
     */
    int avgAssistantReplyLength(String sessionId);

    /**
     * Check if any user message in the session contains a question mark or intent keyword.
     */
    boolean hasUserQuestions(String sessionId);

    /**
     * Paginated message history for a session.
     *
     * @param sessionId session ID
     * @param cursor    message ID cursor (exclusive), 0 to start from beginning/end
     * @param limit     max results
     * @param ascending true for old→new (asc), false for new→old (desc)
     */
    List<MemoryMessage> loadPage(String sessionId, long cursor, int limit, boolean ascending);

    /**
     * Count messages by role (e.g., "user", "assistant", "tool").
     */
    int countByRole(String sessionId, String role);

    /**
     * Aggregated session stats for refinement scoring.
     * Consolidates 7-8 queries into a single GROUP BY.
     */
    record SessionStats(
            int userMsgCount,
            int userCharCount,
            int conversationTurns,
            int toolMsgCount,
            int avgAssistantReplyLen,
            boolean hasUserQuestions
    ) {}

    record ConversationBoundary(int completeTurns, long latestCompleteTurnMessageId) {
        public ConversationBoundary {
            if (completeTurns < 0 || latestCompleteTurnMessageId < 0) {
                throw new IllegalArgumentException("conversation boundary values must not be negative");
            }
            if (completeTurns == 0 && latestCompleteTurnMessageId != 0) {
                throw new IllegalArgumentException("empty conversation cannot have a boundary");
            }
        }
    }

    SessionStats loadSessionStats(String sessionId);

}
