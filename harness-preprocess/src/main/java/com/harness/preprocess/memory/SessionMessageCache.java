package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * Interface for session message cache implementations.
 * Supports in-memory (InMemorySessionMessageCache) and Redis (RedisSessionMessageCache) backends.
 */
public interface SessionMessageCache {

    void setOnEvict(Consumer<String> onEvict);

    List<MemoryMessage> getIfPresent(String sessionId);

    void put(String sessionId, String userId, List<MemoryMessage> messages);

    void append(String sessionId, String userId, MemoryMessage message);

    void remove(String sessionId);

    int size();

    int evictExpired();

    long getGlobalEstimatedBytes();
}
