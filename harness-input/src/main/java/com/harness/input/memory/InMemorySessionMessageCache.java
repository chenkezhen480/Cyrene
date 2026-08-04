package com.harness.input.memory;

import com.harness.core.model.MemoryMessage;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * LRU cache for active session messages with per-user and global eviction:
 * <ol>
 *   <li>Per-user session count: user's oldest session evicted when count exceeds {@code CACHE_MAX_SESSIONS_PER_USER}</li>
 *   <li>Per-user memory cap: user's oldest session evicted when estimated memory exceeds {@code CACHE_MAX_MB_PER_USER}</li>
 *   <li>Global memory cap: globally oldest session evicted until memory drops below {@code CACHE_EVICTION_TARGET_RATIO}% of {@code CACHE_MAX_MB_GLOBAL}</li>
 *   <li>Session TTL: idle sessions expired after {@code CACHE_SESSION_TTL_HOURS}</li>
 * </ol>
 *
 * <p>Evicted messages stay in DB — cache is a hot subset only.</p>
 */
public class InMemorySessionMessageCache implements SessionMessageCache {

    private static final Logger log = LoggerFactory.getLogger(InMemorySessionMessageCache.class);
    private static final long BYTES_PER_MESSAGE = 2500; // ~2.5KB per message estimate

    private final int maxSessionsPerUser;
    private final long maxMemoryBytesPerUser;
    private final long globalMaxMemoryBytes;
    private final double evictionTargetRatio; // 0.0 ~ 1.0
    private final long sessionTtlMs;

    /** sessionId → messages */
    private final Map<String, List<MemoryMessage>> cache = new HashMap<>();
    /** timestamp → set of sessionIds (ordered, for O(log n) oldest lookup) */
    private final TreeMap<Long, Set<String>> timeToSessions = new TreeMap<>();
    /** sessionId → last access timestamp (reverse map for O(1) lookup) */
    private final Map<String, Long> sessionToTime = new HashMap<>();
    /** sessionId → userId */
    private final Map<String, String> sessionUser = new HashMap<>();
    /** userId → set of sessionIds */
    private final Map<String, Set<String>> userSessions = new HashMap<>();
    /** userId → estimated memory bytes */
    private final Map<String, Long> userMemoryBytes = new HashMap<>();
    /** global estimated memory bytes */
    private long globalEstimatedBytes = 0;
    /** callback invoked when a session is evicted (for cross-cache cleanup) */
    private Consumer<String> onEvict;

    public InMemorySessionMessageCache() {
        EnvConfig cfg = EnvConfig.get();
        this.maxSessionsPerUser = cfg.getInt(EnvKey.CACHE_MAX_SESSIONS_PER_USER, 10);
        this.maxMemoryBytesPerUser = (long) cfg.getInt(EnvKey.CACHE_MAX_MB_PER_USER, 2) * 1024 * 1024;
        this.globalMaxMemoryBytes = (long) cfg.getInt(EnvKey.CACHE_MAX_MB_GLOBAL, 4096) * 1024 * 1024;
        this.evictionTargetRatio = cfg.getInt(EnvKey.CACHE_EVICTION_TARGET_RATIO, 50) / 100.0;
        this.sessionTtlMs = (long) cfg.getInt(EnvKey.CACHE_SESSION_TTL_HOURS, 12) * 3600 * 1000;

        log.info("[Cache] InMemorySessionMessageCache initialized: maxPerUser={}, maxMBPerUser={}, globalMaxMB={}, evictionTarget={}%, ttlHours={}",
                maxSessionsPerUser, maxMemoryBytesPerUser / (1024 * 1024),
                globalMaxMemoryBytes / (1024 * 1024),
                (int) (evictionTargetRatio * 100), sessionTtlMs / 3600000);
    }

    @Override
    public void setOnEvict(Consumer<String> onEvict) {
        this.onEvict = onEvict;
    }

    @Override
    public synchronized List<MemoryMessage> getIfPresent(String sessionId) {
        if (isExpired(sessionId)) {
            evictSessionInternal(sessionId, true);
            return null;
        }
        List<MemoryMessage> msgs = cache.get(sessionId);
        if (msgs != null) {
            touchSession(sessionId);
        }
        return msgs;
    }

    @Override
    public synchronized void put(String sessionId, String userId, List<MemoryMessage> messages) {
        List<MemoryMessage> old = cache.put(sessionId, new ArrayList<>(messages));
        touchSession(sessionId);

        long newBytes = (long) messages.size() * BYTES_PER_MESSAGE;
        long oldBytes = 0;

        if (old != null) {
            oldBytes = (long) old.size() * BYTES_PER_MESSAGE;
            globalEstimatedBytes -= oldBytes;
            String existingUser = sessionUser.get(sessionId);
            if (existingUser != null && !existingUser.equals(userId)) {
                Set<String> oldUserSessions = userSessions.get(existingUser);
                if (oldUserSessions != null) {
                    oldUserSessions.remove(sessionId);
                    if (oldUserSessions.isEmpty()) userSessions.remove(existingUser);
                }
                userMemoryBytes.merge(existingUser, -oldBytes, Long::sum);
                if (userMemoryBytes.getOrDefault(existingUser, 0L) <= 0) userMemoryBytes.remove(existingUser);
            } else if (existingUser != null) {
                userMemoryBytes.merge(existingUser, -oldBytes, Long::sum);
            }
        }

        globalEstimatedBytes += newBytes;
        sessionUser.put(sessionId, userId);
        userSessions.computeIfAbsent(userId, k -> new HashSet<>()).add(sessionId);
        userMemoryBytes.merge(userId, newBytes, Long::sum);

        enforcePerUserLimits(userId);
        enforceGlobalMemoryLimit();
    }

    @Override
    public synchronized void append(String sessionId, String userId, MemoryMessage message) {
        List<MemoryMessage> msgs = cache.computeIfAbsent(sessionId, k -> new ArrayList<>());
        msgs.add(message);
        touchSession(sessionId);

        globalEstimatedBytes += BYTES_PER_MESSAGE;
        sessionUser.putIfAbsent(sessionId, userId);
        userSessions.computeIfAbsent(userId, k -> new HashSet<>()).add(sessionId);
        userMemoryBytes.merge(userId, (long) BYTES_PER_MESSAGE, Long::sum);

        enforcePerUserLimits(userId);
        enforceGlobalMemoryLimit();
    }

    @Override
    public synchronized void remove(String sessionId) {
        evictSessionInternal(sessionId, true);
    }

    @Override
    public synchronized int size() {
        return cache.size();
    }

    @Override
    public synchronized int evictExpired() {
        long now = System.currentTimeMillis();
        long threshold = now - sessionTtlMs;
        int evicted = 0;

        List<String> expired = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> entry : timeToSessions.entrySet()) {
            if (entry.getKey() > threshold) break;
            expired.addAll(entry.getValue());
        }
        for (String sid : expired) {
            evictSessionInternal(sid, true);
            evicted++;
        }
        if (evicted > 0) {
            log.info("[Cache] Evicted {} expired sessions (TTL={}h), remaining={}", evicted, sessionTtlMs / 3600000, cache.size());
        }
        return evicted;
    }

    @Override
    public synchronized long getGlobalEstimatedBytes() {
        return globalEstimatedBytes;
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

    private void evictSessionInternal(String sessionId, boolean notifyEvict) {
        List<MemoryMessage> old = cache.remove(sessionId);

        Long ts = sessionToTime.remove(sessionId);
        if (ts != null) {
            removeFromTimeIndex(ts, sessionId);
        }

        long freed = 0;
        if (old != null) {
            freed = (long) old.size() * BYTES_PER_MESSAGE;
            globalEstimatedBytes -= freed;
        }

        String userId = sessionUser.remove(sessionId);
        if (userId != null) {
            Set<String> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    userSessions.remove(userId);
                }
            }
            userMemoryBytes.merge(userId, -freed, Long::sum);
            if (userMemoryBytes.getOrDefault(userId, 0L) <= 0) {
                userMemoryBytes.remove(userId);
            }
        }

        log.debug("[Cache] Evicted session: {}, freed {} messages (user={}, notify={})", sessionId, old != null ? old.size() : 0, userId, notifyEvict);

        if (notifyEvict && onEvict != null) {
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

    private void enforcePerUserLimits(String userId) {
        Set<String> sessions = userSessions.get(userId);
        if (sessions == null) return;

        while (sessions.size() > maxSessionsPerUser) {
            String oldest = findOldestSession(sessions);
            if (oldest == null) break;
            log.warn("[Cache] Per-user session limit exceeded: user={}, sessions={}, max={}, evicting {}",
                    userId, sessions.size(), maxSessionsPerUser, oldest);
            evictSessionInternal(oldest, true);
        }

        while (userMemoryBytes.getOrDefault(userId, 0L) > maxMemoryBytesPerUser) {
            sessions = userSessions.get(userId);
            if (sessions == null || sessions.isEmpty()) break;
            String oldest = findOldestSession(sessions);
            if (oldest == null) break;
            log.warn("[Cache] Per-user memory limit exceeded: user={}, bytes={}MB > {}MB, evicting {}",
                    userId, userMemoryBytes.get(userId) / (1024 * 1024),
                    maxMemoryBytesPerUser / (1024 * 1024), oldest);
            evictSessionInternal(oldest, true);
        }
    }

    private void enforceGlobalMemoryLimit() {
        long targetBytes = (long) (globalMaxMemoryBytes * evictionTargetRatio);
        if (globalEstimatedBytes <= globalMaxMemoryBytes) {
            return;
        }

        log.warn("[Cache] Global memory limit exceeded: {}MB > {}MB, evicting to {}MB",
                globalEstimatedBytes / (1024 * 1024),
                globalMaxMemoryBytes / (1024 * 1024),
                targetBytes / (1024 * 1024));

        while (globalEstimatedBytes > targetBytes && !cache.isEmpty()) {
            String oldest = findGloballyOldestSession();
            if (oldest == null) break;
            evictSessionInternal(oldest, true);
        }

        log.debug("[Cache] After global eviction: sessions={}, globalMB={}",
                cache.size(), globalEstimatedBytes / (1024 * 1024));
    }

    private String findOldestSession(Set<String> sessions) {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (String sid : sessions) {
            Long t = sessionToTime.get(sid);
            if (t != null && t < oldestTime) {
                oldestTime = t;
                oldest = sid;
            }
        }
        return oldest;
    }

    private String findGloballyOldestSession() {
        Map.Entry<Long, Set<String>> first = timeToSessions.firstEntry();
        if (first == null || first.getValue().isEmpty()) return null;
        return first.getValue().iterator().next();
    }
}
