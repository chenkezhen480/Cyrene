package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;

import java.util.List;

/**
 * No-op message store. Used when HARNESS_MEMORY_STORE=none.
 */
public class NoOpMessageStore implements MessageStore {
    @Override public void save(String sessionId, String role, String content, boolean isSummary) {}
    @Override public List<MemoryMessage> loadForContext(String sessionId) { return List.of(); }
    @Override public int countUserMessages(String sessionId) { return 0; }
    @Override public int sumUserContentLength(String sessionId) { return 0; }
    @Override public int countConversationTurns(String sessionId) { return 0; }
    @Override public int countToolMessages(String sessionId) { return 0; }
    @Override public int avgAssistantReplyLength(String sessionId) { return 0; }
    @Override public boolean hasUserQuestions(String sessionId) { return false; }
}
