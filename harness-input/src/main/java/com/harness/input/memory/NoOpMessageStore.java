package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.model.PageResponse;
import java.util.List;
import java.util.Optional;

/**
 * No-op message store. Used when HARNESS_AUDIT_STORE=none.
 */
public class NoOpMessageStore implements MessageStore {
    @Override public long save(MessageWrite message) { return 0; }
    @Override public List<Long> saveBatch(List<MessageWrite> messages) {
        return messages == null ? List.of() : java.util.Collections.nCopies(messages.size(), 0L);
    }
    @Override public List<MemoryMessage> loadForContext(String sessionId) { return List.of(); }
    @Override public Optional<MemoryMessage> findByIdAndSession(long messageId, String sessionId) {
        return Optional.empty();
    }
    @Override public int countUserMessages(String sessionId) { return 0; }
    @Override public int sumUserContentLength(String sessionId) { return 0; }
    @Override public int countConversationTurns(String sessionId) { return 0; }
    @Override public ConversationBoundary findConversationBoundary(String sessionId) {
        return new ConversationBoundary(0, 0);
    }
    @Override public int countToolMessages(String sessionId) { return 0; }
    @Override public int avgAssistantReplyLength(String sessionId) { return 0; }
    @Override public boolean hasUserQuestions(String sessionId) { return false; }
    @Override public List<MemoryMessage> loadPage(String sessionId, long cursor, int limit, boolean ascending) { return List.of(); }
    @Override public int countByRole(String sessionId, String role) { return 0; }
    @Override public SessionStats loadSessionStats(String sessionId) { return new SessionStats(0, 0, 0, 0, 0, false); }
    @Override public DeletionResult deleteToolMessages(
            String sessionId, java.util.function.LongPredicate retainedByKnowledge) {
        return new DeletionResult(0, 0);
    }
}
