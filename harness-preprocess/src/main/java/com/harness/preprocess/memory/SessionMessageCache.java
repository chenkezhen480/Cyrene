package com.harness.preprocess.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LRU cache for active session messages with three-layer eviction:
 * <ol>
 *   <li>Per-session message cap: oldest messages evicted when count exceeds {@code CACHE_MAX_MESSAGES_PER_SESSION}</li>
 *   <li>Total memory cap: coldest 50% sessions evicted when estimated memory exceeds {@code CACHE_MAX_MB}</li>
 *   <li>Session TTL: idle sessions expired after {@code CACHE_SESSION_TTL_HOURS}</li>
 * </ol>
 *
 * <p>Evicted messages stay in DB — cache is a hot subset only.</p>
 */
public class SessionMessageCache {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageCache.class);
    private static final long BYTES_PER_MESSAGE = 2500; // ~2.5KB per message estimate

    private final int maxSessions;
    private final int maxMessagesPerSession;
    private final long maxMemoryBytes;
    private final long sessionTtlMs;

    /** sessionId → messages (LRU access-ordered) */
    private final Map<String, List<MemoryMessage>> cache;
    /** sessionId → last access timestamp */
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();
    /** estimated total memory usage in bytes */
    private long estimatedBytes = 0;

    public SessionMessageCache() {
        EnvConfig cfg = EnvConfig.get();
        this.maxSessions = cfg.getInt(EnvKey.CACHE_MAX_SESSIONS, 10);
        this.maxMessagesPerSession = cfg.getInt(EnvKey.CACHE_MAX_MESSAGES_PER_SESSION, 10);
        this.maxMemoryBytes = (long) cfg.getInt(EnvKey.CACHE_MAX_MB, 20) * 1024 * 1024;
        this.sessionTtlMs = (long) cfg.getInt(EnvKey.CACHE_SESSION_TTL_HOURS, 12) * 3600 * 1000;

        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<MemoryMessage>> eldest) {
                return size() > maxSessions;
            }
        };

        log.info("[Cache] SessionMessageCache initialized: maxSessions={}, maxMsgPerSession={}, maxMB={}, ttlHours={}",
                maxSessions, maxMessagesPerSession, maxMemoryBytes / (1024 * 1024), sessionTtlMs / 3600000);
    }

    /**
     * Get cached messages, or null on miss. Returns null if session expired.
     */
    public synchronized List<MemoryMessage> getIfPresent(String sessionId) {
        if (isExpired(sessionId)) {
            evictSession(sessionId);
            return null;
        }
        List<MemoryMessage> msgs = cache.get(sessionId);
        if (msgs != null) {
            lastAccess.put(sessionId, System.currentTimeMillis());
        }
        return msgs;
    }

    /**
     * Populate cache after DB load. Trims to maxMessagesPerSession (keeps newest).
     */
    public synchronized void put(String sessionId, List<MemoryMessage> messages) {
        List<MemoryMessage> trimmed = trimToLimit(messages);
        List<MemoryMessage> old = cache.put(sessionId, new ArrayList<>(trimmed));
        lastAccess.put(sessionId, System.currentTimeMillis());

        // Update memory estimate
        if (old != null) {
            estimatedBytes -= (long) old.size() * BYTES_PER_MESSAGE;
        }
        estimatedBytes += (long) trimmed.size() * BYTES_PER_MESSAGE;

        enforceMemoryLimit();
    }

    /**
     * Append a message to cache. Trims oldest if per-session limit exceeded.
     * Checks total memory after append.
     */
    public synchronized void append(String sessionId, MemoryMessage message) {
        List<MemoryMessage> msgs = cache.computeIfAbsent(sessionId, k -> new ArrayList<>());
        msgs.add(message);
        lastAccess.put(sessionId, System.currentTimeMillis());
        estimatedBytes += BYTES_PER_MESSAGE;

        // Per-session message limit: evict oldest
        if (msgs.size() > maxMessagesPerSession) {
            int overflow = msgs.size() - maxMessagesPerSession;
            msgs.subList(0, overflow).clear();
            estimatedBytes -= (long) overflow * BYTES_PER_MESSAGE;
            log.debug("[Cache] Session {} trimmed {} old messages (limit={})", sessionId, overflow, maxMessagesPerSession);
        }

        enforceMemoryLimit();
    }

    /**
     * Invalidate after compression (forces DB reload on next read).
     */
    public synchronized void invalidate(String sessionId) {
        List<MemoryMessage> old = cache.remove(sessionId);
        lastAccess.remove(sessionId);
        if (old != null) {
            estimatedBytes -= (long) old.size() * BYTES_PER_MESSAGE;
        }
    }

    /**
     * Remove on session close.
     */
    public synchronized void remove(String sessionId) {
        List<MemoryMessage> old = cache.remove(sessionId);
        lastAccess.remove(sessionId);
        if (old != null) {
            estimatedBytes -= (long) old.size() * BYTES_PER_MESSAGE;
        }
    }

    public synchronized boolean contains(String sessionId) {
        if (isExpired(sessionId)) {
            evictSession(sessionId);
            return false;
        }
        return cache.containsKey(sessionId);
    }

    public synchronized int size() {
        return cache.size();
    }

    /**
     * Evict all expired sessions. Call periodically from cleanup scheduler.
     */
    public synchronized int evictExpired() {
        long now = System.currentTimeMillis();
        int evicted = 0;
        Iterator<Map.Entry<String, Long>> it = lastAccess.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (now - entry.getValue() > sessionTtlMs) {
                String sid = entry.getKey();
                List<MemoryMessage> old = cache.remove(sid);
                if (old != null) {
                    estimatedBytes -= (long) old.size() * BYTES_PER_MESSAGE;
                }
                it.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            log.info("[Cache] Evicted {} expired sessions (TTL={}h), remaining={}", evicted, sessionTtlMs / 3600000, cache.size());
        }
        return evicted;
    }

    public synchronized long getEstimatedBytes() {
        return estimatedBytes;
    }

    // ========== Internal ==========

    private boolean isExpired(String sessionId) {
        Long last = lastAccess.get(sessionId);
        return last != null && (System.currentTimeMillis() - last) > sessionTtlMs;
    }

    private void evictSession(String sessionId) {
        List<MemoryMessage> old = cache.remove(sessionId);
        lastAccess.remove(sessionId);
        if (old != null) {
            estimatedBytes -= (long) old.size() * BYTES_PER_MESSAGE;
            log.debug("[Cache] Evicted expired session: {}, freed {} messages", sessionId, old.size());
        }
    }

    /**
     * Trim message list to maxMessagesPerSession, keeping the newest messages.
     */
    private List<MemoryMessage> trimToLimit(List<MemoryMessage> messages) {
        if (messages.size() <= maxMessagesPerSession) {
            return messages;
        }
        int start = messages.size() - maxMessagesPerSession;
        log.debug("[Cache] Trimming messages: {} → {} (keeping newest)", messages.size(), maxMessagesPerSession);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    /**
     * When total estimated memory exceeds maxMemoryBytes, evict coldest 50% sessions by LRU.
     */
    private void enforceMemoryLimit() {
        if (estimatedBytes <= maxMemoryBytes) {
            return;
        }

        int targetEvictions = Math.max(1, cache.size() / 2);
        log.warn("[Cache] Memory limit exceeded: {}MB > {}MB, evicting {} coldest sessions",
                estimatedBytes / (1024 * 1024), maxMemoryBytes / (1024 * 1024), targetEvictions);

        // LinkedHashMap iteration order = insertion order (not access order) for eviction list
        // We need to sort by lastAccess time to evict the coldest
        List<String> sortedSessions = lastAccess.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(targetEvictions)
                .map(Map.Entry::getKey)
                .toList();

        for (String sid : sortedSessions) {
            List<MemoryMessage> old = cache.remove(sid);
            lastAccess.remove(sid);
            if (old != null) {
                estimatedBytes -= (long) old.size() * BYTES_PER_MESSAGE;
            }
        }

        log.info("[Cache] After eviction: sessions={}, estimatedMB={}", cache.size(), estimatedBytes / (1024 * 1024));
    }
}
