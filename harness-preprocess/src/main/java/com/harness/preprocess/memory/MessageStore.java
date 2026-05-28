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
}
