package com.harness.input.memory;

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

    default SessionCacheLookup lookup(String sessionId) {
        List<MemoryMessage> messages = getIfPresent(sessionId);
        return messages != null ? SessionCacheLookup.hit(messages) : SessionCacheLookup.miss();
    }

    void put(String sessionId, String userId, List<MemoryMessage> messages);

    default boolean putObserved(String sessionId, String userId, List<MemoryMessage> messages) {
        put(sessionId, userId, messages);
        return true;
    }

    void append(String sessionId, String userId, MemoryMessage message);

    void remove(String sessionId);

    int size();

    int evictExpired();

    long getGlobalEstimatedBytes();

    default SessionCacheMetrics metrics() {
        return SessionCacheMetrics.noop();
    }

    default SessionCacheMetrics.Snapshot metricsSnapshot() {
        return metrics().snapshot(size(), getGlobalEstimatedBytes());
    }
}
