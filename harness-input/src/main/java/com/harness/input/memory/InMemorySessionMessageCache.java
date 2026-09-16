package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Disposable read cache for active session messages, keyed by a sliding idle TTL.
 *
 * <p>The message store is the single source of truth: entries exist only to make relocating a
 * session's history cheaper. Entries expire when idle for {@code CACHE_SESSION_TTL_HOURS} and
 * are otherwise never removed — there is no LRU, no per-user quota and no memory cap. Losing
 * an entry is always safe; the next lookup reloads from the store.
 *
 * <p>An entry can only be created by {@link #put} with a complete history. Appending to a
 * missing entry stays missing rather than publishing a history that starts mid-conversation.
 */
public class InMemorySessionMessageCache implements SessionMessageCache {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionMessageCache.class);

    private final long sessionTtlMs;
    private final SessionCacheMetrics metrics = new SessionCacheMetrics("memory");

    /** sessionId → messages */
    private final Map<String, List<MemoryMessage>> cache = new HashMap<>();
    /** last access timestamp → sessionIds, ordered so the expiry sweep can stop early */
    private final TreeMap<Long, Set<String>> timeToSessions = new TreeMap<>();
    /** sessionId → last access timestamp */
    private final Map<String, Long> sessionToTime = new HashMap<>();
    /** callback invoked when a session is dropped (for cross-cache cleanup) */
    private Consumer<String> onEvict;

    public InMemorySessionMessageCache() {
        this.sessionTtlMs = (long) EnvConfig.get()
                .getInt(EnvKey.CACHE_SESSION_TTL_HOURS, 12) * 3600 * 1000;
        log.info("[Cache] InMemorySessionMessageCache initialized: ttlHours={}",
                sessionTtlMs / 3600000);
    }

    @Override
    public void setOnEvict(Consumer<String> onEvict) {
        this.onEvict = onEvict;
    }

    @Override
    public synchronized SessionCacheLookup lookup(String sessionId) {
        if (isExpired(sessionId)) {
            discard(sessionId);
            return SessionCacheLookup.miss();
        }
        List<MemoryMessage> msgs = cache.get(sessionId);
        if (msgs != null) {
            touchSession(sessionId);
            return SessionCacheLookup.hit(msgs);
        }
        return SessionCacheLookup.miss();
    }

    @Override
    public synchronized List<MemoryMessage> getIfPresent(String sessionId) {
        return lookup(sessionId).messages();
    }

    @Override
    public synchronized void put(String sessionId, String userId, List<MemoryMessage> messages) {
        putObserved(sessionId, userId, messages);
    }

    @Override
    public synchronized boolean putObserved(
            String sessionId,
            String userId,
            List<MemoryMessage> messages
    ) {
        cache.put(sessionId, new ArrayList<>(messages));
        touchSession(sessionId);
        return cache.containsKey(sessionId);
    }

    @Override
    public synchronized void appendIfPresent(
            String sessionId, String userId, MemoryMessage message) {
        // A miss must stay a miss. Rebuilding the entry from this one message would publish a
        // history that starts mid-conversation — for a Tool round that means tool results with
        // no declaring assistant message, which every OpenAI-compatible provider rejects.
        // Leaving the entry absent lets the next lookup() reload the full history from the store.
        if (lookup(sessionId).outcome() != SessionCacheLookup.Outcome.HIT) {
            return;
        }
        cache.get(sessionId).add(message);
        touchSession(sessionId);
    }

    @Override
    public synchronized void remove(String sessionId) {
        discard(sessionId);
    }

    @Override
    public synchronized int size() {
        return cache.size();
    }

    @Override
    public synchronized int evictExpired() {
        long threshold = System.currentTimeMillis() - sessionTtlMs;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> entry : timeToSessions.entrySet()) {
            if (entry.getKey() > threshold) break;
            expired.addAll(entry.getValue());
        }
        for (String sid : expired) {
            discard(sid);
        }
        if (!expired.isEmpty()) {
            log.info("[Cache] Expired {} idle sessions (TTL={}h), remaining={}",
                    expired.size(), sessionTtlMs / 3600000, cache.size());
        }
        return expired.size();
    }

    @Override
    public SessionCacheMetrics metrics() {
        return metrics;
    }

    // ========== Internal ==========

    private void touchSession(String sessionId) {
        long now = System.currentTimeMillis();
        Long oldTime = sessionToTime.put(sessionId, now);
        if (oldTime != null) {
            removeFromTimeIndex(oldTime, sessionId);
        }
        timeToSessions.computeIfAbsent(now, k -> new HashSet<>()).add(sessionId);
    }

    private boolean isExpired(String sessionId) {
        Long last = sessionToTime.get(sessionId);
        return last != null && (System.currentTimeMillis() - last) > sessionTtlMs;
    }

    private void discard(String sessionId) {
        boolean present = cache.remove(sessionId) != null;
        Long ts = sessionToTime.remove(sessionId);
        if (ts != null) {
            removeFromTimeIndex(ts, sessionId);
        }
        if (!present && ts == null) {
            return;
        }
        log.debug("[Cache] Dropped session: {}", sessionId);
        if (onEvict != null) {
            try {
                onEvict.accept(sessionId);
            } catch (Exception e) {
                log.warn("[Cache] onEvict callback failed for session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    private void removeFromTimeIndex(long timestamp, String sessionId) {
        Set<String> sessions = timeToSessions.get(timestamp);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                timeToSessions.remove(timestamp);
            }
        }
    }
}
