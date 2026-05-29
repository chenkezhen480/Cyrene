package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;

import java.util.List;

/**
 * Persistence interface for conversation messages within a session.
 */
public interface MessageStore {
    void save(String sessionId, String role, String content, boolean isSummary);
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
}
