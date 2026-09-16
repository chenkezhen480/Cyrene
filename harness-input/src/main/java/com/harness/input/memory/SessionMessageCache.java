package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * Disposable read cache over the session message store.
 *
 * <p>The store is the single source of truth for conversation history; this cache only makes
 * reloading it cheaper. Its sole lifecycle is a sliding idle TTL — there is no active
 * eviction, no per-user quota and no LRU, so a cache entry may disappear at any moment and
 * the next lookup simply reloads from the store.
 *
 * <p>That contract is what {@link #appendIfPresent} protects: an entry may only be created by
 * {@link #put} with a complete history, never grown from nothing. Appending to a missing entry
 * would publish a history that starts mid-conversation, which for a Tool round means tool
 * results with no declaring assistant message.
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

    /**
     * Appends only when the session is already cached, and refreshes its idle TTL. Does nothing
     * when absent — see the class comment for why creating an entry here is never allowed.
     */
    void appendIfPresent(String sessionId, String userId, MemoryMessage message);

    void remove(String sessionId);

    int size();

    int evictExpired();

    default SessionCacheMetrics metrics() {
        return SessionCacheMetrics.noop();
    }

    default SessionCacheMetrics.Snapshot metricsSnapshot() {
        return metrics().snapshot(size());
    }
}
