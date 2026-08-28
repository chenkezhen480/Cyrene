package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;

import java.util.List;

/**
 * Persistence interface for conversation messages within a session.
 */
public interface MessageStore {
    /**
     * Save a message with structured content blocks.
     */
    void save(String sessionId, String role, List<MessageBlock> content, boolean isSummary);

    List<MemoryMessage> loadForContext(String sessionId);
    int countUserMessages(String sessionId);
    int sumUserContentLength(String sessionId);

    /**
     * Count conversation turns (user+assistant message pairs) in a session.
     */
    int countConversationTurns(String sessionId);

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

    SessionStats loadSessionStats(String sessionId);

    /**
     * Delete all messages belonging to a session.
     *
     * @param sessionId session ID
     * @return number of deleted messages
     */
    int deleteBySession(String sessionId);

    /** Delete Tool calls/results when minor compression strips Tool context. */
    int deleteToolMessages(String sessionId);
}
