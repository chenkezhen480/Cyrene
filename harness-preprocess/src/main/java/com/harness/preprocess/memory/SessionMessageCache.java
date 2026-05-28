package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LRU cache for active session messages.
 * Bounded by HARNESS_MEMORY_CACHE_MAX_SESSIONS (default 100).
 *
 * Memory estimate: ~50KB per session (20 messages × 2.5KB avg)
 *   100 sessions ≈ 5MB, 500 sessions ≈ 25MB
 *
 * Strategy:
 * - Read: cache hit → return; miss → load from DB → populate
 * - Write: append to cache + persist to DB
 * - Compression: invalidate → next read reloads from DB
 * - Eviction: LRU when cache full → evicted session next read hits DB
 */
public class SessionMessageCache {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageCache.class);

    private final int maxSessions;
    private final Map<String, List<MemoryMessage>> cache;

    public SessionMessageCache() {
        this.maxSessions = EnvConfig.get().getInt("HARNESS_MEMORY_CACHE_MAX_SESSIONS", 100);
        // LRU: access-ordered, removeEldestEntry for automatic eviction
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<MemoryMessage>> eldest) {
                boolean shouldEvict = size() > maxSessions;
                if (shouldEvict) {
                    log.debug("LRU evicting session cache: {}", eldest.getKey());
                }
                return shouldEvict;
            }
        };
    }

    /**
     * Get cached messages, or null on miss.
     */
    public synchronized List<MemoryMessage> getIfPresent(String sessionId) {
        return cache.get(sessionId);
    }

    /**
     * Populate cache after DB load.
     */
    public synchronized void put(String sessionId, List<MemoryMessage> messages) {
        cache.put(sessionId, new ArrayList<>(messages));
    }

    /**
     * Append a message to cache (after DB save).
     */
    public synchronized void append(String sessionId, MemoryMessage message) {
        cache.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }

    /**
     * Invalidate after compression (forces DB reload on next read).
     */
    public synchronized void invalidate(String sessionId) {
        cache.remove(sessionId);
    }

    /**
     * Remove on session close.
     */
    public synchronized void remove(String sessionId) {
        cache.remove(sessionId);
    }

    public synchronized boolean contains(String sessionId) {
        return cache.containsKey(sessionId);
    }

    public synchronized int size() {
        return cache.size();
    }
}
