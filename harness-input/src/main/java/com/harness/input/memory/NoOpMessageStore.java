package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;

import java.util.List;

/**
 * No-op message store. Used when HARNESS_AUDIT_STORE=none.
 */
public class NoOpMessageStore implements MessageStore {
    @Override public void save(String sessionId, String role, List<MessageBlock> content, boolean isSummary) {}
    @Override public List<MemoryMessage> loadForContext(String sessionId) { return List.of(); }
    @Override public int countUserMessages(String sessionId) { return 0; }
    @Override public int sumUserContentLength(String sessionId) { return 0; }
    @Override public int countConversationTurns(String sessionId) { return 0; }
    @Override public int countToolMessages(String sessionId) { return 0; }
    @Override public int avgAssistantReplyLength(String sessionId) { return 0; }
    @Override public boolean hasUserQuestions(String sessionId) { return false; }
    @Override public List<MemoryMessage> loadPage(String sessionId, long cursor, int limit, boolean ascending) { return List.of(); }
    @Override public int countByRole(String sessionId, String role) { return 0; }
    @Override public SessionStats loadSessionStats(String sessionId) { return new SessionStats(0, 0, 0, 0, 0, false); }
    @Override public int deleteBySession(String sessionId) { return 0; }
}
