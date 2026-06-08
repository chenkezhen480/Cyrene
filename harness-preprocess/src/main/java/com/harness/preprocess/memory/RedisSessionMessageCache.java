package com.harness.preprocess.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.MemoryMessage;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.env.RedisConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.function.Consumer;

/**
 * Redis-backed distributed session message cache.
 * Uses Redis for cross-instance cache sharing with native TTL expiration.
 * All operations are best-effort — on Redis failure, degrades to cache-miss (DB fallback).
 */
public class RedisSessionMessageCache implements SessionMessageCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionMessageCache.class);
    private static final long BYTES_PER_MESSAGE = 2500;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final TypeReference<List<MemoryMessage>> MSG_LIST_TYPE = new TypeReference<>() {};

    private final String prefix;
    private final int ttlSeconds;
    private final int maxSessionsPerUser;
    private final long maxMemoryBytesPerUser;
    private final long globalMaxMemoryBytes;
    private final double evictionTargetRatio;

    private Consumer<String> onEvict;

    public RedisSessionMessageCache() {
        EnvConfig cfg = EnvConfig.get();
        this.prefix = cfg.getString(EnvKey.MEMORY_REDIS_KEY_PREFIX, "harness");
        this.ttlSeconds = cfg.getInt(EnvKey.MEMORY_REDIS_TTL_MINUTES, 720) * 60;
        this.maxSessionsPerUser = cfg.getInt(EnvKey.CACHE_MAX_SESSIONS_PER_USER, 10);
        this.maxMemoryBytesPerUser = (long) cfg.getInt(EnvKey.CACHE_MAX_MB_PER_USER, 2) * 1024 * 1024;
        this.globalMaxMemoryBytes = (long) cfg.getInt(EnvKey.CACHE_MAX_MB_GLOBAL, 4096) * 1024 * 1024;
        this.evictionTargetRatio = cfg.getInt(EnvKey.CACHE_EVICTION_TARGET_RATIO, 50) / 100.0;

        log.info("[Redis-Cache] RedisSessionMessageCache initialized: prefix={}, ttl={}s, maxPerUser={}, maxMBPerUser={}, globalMaxMB={}",
                prefix, ttlSeconds, maxSessionsPerUser, maxMemoryBytesPerUser / (1024 * 1024), globalMaxMemoryBytes / (1024 * 1024));
    }

    @Override
    public void setOnEvict(Consumer<String> onEvict) {
        this.onEvict = onEvict;
    }

    @Override
    public List<MemoryMessage> getIfPresent(String sessionId) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            String json = jedis.get(msgKey(sessionId));
            if (json == null) return null;

            List<MemoryMessage> messages = MAPPER.readValue(json, MSG_LIST_TYPE);
            // Update access time
            jedis.zadd(accessKey(), System.currentTimeMillis(), sessionId);
            return messages;
        } catch (Exception e) {
            log.warn("[Redis-Cache] getIfPresent failed for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String sessionId, String userId, List<MemoryMessage> messages) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            // Calculate old bytes if session already exists
            long oldBytes = 0;
            String existingUserId = jedis.hget(metaKey(sessionId), "userId");
            String oldBytesStr = jedis.hget(metaKey(sessionId), "bytes");
            if (oldBytesStr != null) {
                oldBytes = Long.parseLong(oldBytesStr);
            }

            long newBytes = (long) messages.size() * BYTES_PER_MESSAGE;

            // Store messages with TTL
            String json = MAPPER.writeValueAsString(new ArrayList<>(messages));
            jedis.setex(msgKey(sessionId), ttlSeconds, json);

            // Store metadata with same TTL
            jedis.hset(metaKey(sessionId), Map.of("userId", userId, "bytes", String.valueOf(newBytes)));
            jedis.expire(metaKey(sessionId), ttlSeconds);

            // Track user sessions
            jedis.sadd(userSessionsKey(userId), sessionId);

            // Update access time
            jedis.zadd(accessKey(), System.currentTimeMillis(), sessionId);

            // Update byte counters (delta)
            long delta = newBytes - oldBytes;
            if (delta != 0) {
                jedis.incrBy(globalBytesKey(), delta);
            }
            // Adjust old user's bytes if user changed
            if (existingUserId != null && !existingUserId.equals(userId) && oldBytes > 0) {
                jedis.decrBy(userBytesKey(existingUserId), oldBytes);
                jedis.srem(userSessionsKey(existingUserId), sessionId);
            }
            jedis.incrBy(userBytesKey(userId), newBytes - (existingUserId != null && existingUserId.equals(userId) ? oldBytes : 0));

            // Enforce limits
            enforcePerUserLimits(jedis, userId);
            enforceGlobalMemoryLimit(jedis);
        } catch (Exception e) {
            log.warn("[Redis-Cache] put failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void append(String sessionId, String userId, MemoryMessage message) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            // Fetch current messages
            String json = jedis.get(msgKey(sessionId));
            List<MemoryMessage> messages;
            if (json != null) {
                messages = MAPPER.readValue(json, MSG_LIST_TYPE);
            } else {
                messages = new ArrayList<>();
            }

            messages.add(message);

            // Re-store with refreshed TTL
            String newJson = MAPPER.writeValueAsString(messages);
            jedis.setex(msgKey(sessionId), ttlSeconds, newJson);

            // Update metadata
            jedis.hset(metaKey(sessionId), Map.of("userId", userId, "bytes", String.valueOf((long) messages.size() * BYTES_PER_MESSAGE)));
            jedis.expire(metaKey(sessionId), ttlSeconds);

            // Track user sessions
            jedis.sadd(userSessionsKey(userId), sessionId);

            // Update access time
            jedis.zadd(accessKey(), System.currentTimeMillis(), sessionId);

            // Update byte counters
            jedis.incrBy(userBytesKey(userId), BYTES_PER_MESSAGE);
            jedis.incrBy(globalBytesKey(), BYTES_PER_MESSAGE);

            // Enforce limits
            enforcePerUserLimits(jedis, userId);
            enforceGlobalMemoryLimit(jedis);
        } catch (Exception e) {
            log.warn("[Redis-Cache] append failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void remove(String sessionId) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            evictSessionInternal(jedis, sessionId, true);
        } catch (Exception e) {
            log.warn("[Redis-Cache] remove failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public int size() {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            return (int) jedis.zcard(accessKey());
        } catch (Exception e) {
            log.warn("[Redis-Cache] size failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int evictExpired() {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            // Find sessions whose msg key no longer exists (expired by TTL)
            List<String> allSessions = new ArrayList<>(jedis.zrange(accessKey(), 0, -1));
            int evicted = 0;
            for (String sid : allSessions) {
                if (!jedis.exists(msgKey(sid))) {
                    evictSessionInternal(jedis, sid, true);
                    evicted++;
                }
            }
            if (evicted > 0) {
                log.info("[Redis-Cache] Evicted {} expired sessions (TTL={}s)", evicted, ttlSeconds);
            }
            return evicted;
        } catch (Exception e) {
            log.warn("[Redis-Cache] evictExpired failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getGlobalEstimatedBytes() {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            String val = jedis.get(globalBytesKey());
            return val != null ? Long.parseLong(val) : 0;
        } catch (Exception e) {
            log.warn("[Redis-Cache] getGlobalEstimatedBytes failed: {}", e.getMessage());
            return 0;
        }
    }

    // ========== Internal ==========

    private void evictSessionInternal(Jedis jedis, String sessionId, boolean notifyEvict) {
        // Get metadata before deletion
        String userId = jedis.hget(metaKey(sessionId), "userId");
        String bytesStr = jedis.hget(metaKey(sessionId), "bytes");
        long freed = bytesStr != null ? Long.parseLong(bytesStr) : 0;

        // Delete msg and meta keys
        jedis.del(msgKey(sessionId));
        jedis.del(metaKey(sessionId));

        // Remove from tracking structures
        jedis.zrem(accessKey(), sessionId);
        if (userId != null) {
            jedis.srem(userSessionsKey(userId), sessionId);
            if (freed > 0) {
                jedis.decrBy(userBytesKey(userId), freed);
            }
        }
        if (freed > 0) {
            jedis.decrBy(globalBytesKey(), freed);
        }

        log.debug("[Redis-Cache] Evicted session: {}, freed {} bytes (user={}, notify={})", sessionId, freed, userId, notifyEvict);

        if (notifyEvict && onEvict != null) {
            try {
                onEvict.accept(sessionId);
            } catch (Exception e) {
                log.warn("[Redis-Cache] onEvict callback failed for session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    private void enforcePerUserLimits(Jedis jedis, String userId) {
        // Session count limit
        long sessionCount = jedis.scard(userSessionsKey(userId));
        while (sessionCount > maxSessionsPerUser) {
            String oldest = findOldestUserSession(jedis, userId);
            if (oldest == null) break;
            log.warn("[Redis-Cache] Per-user session limit exceeded: user={}, sessions={}, max={}, evicting {}",
                    userId, sessionCount, maxSessionsPerUser, oldest);
            evictSessionInternal(jedis, oldest, true);
            sessionCount = jedis.scard(userSessionsKey(userId));
        }

        // Per-user memory limit
        String userBytesStr = jedis.get(userBytesKey(userId));
        long userBytes = userBytesStr != null ? Long.parseLong(userBytesStr) : 0;
        while (userBytes > maxMemoryBytesPerUser) {
            String oldest = findOldestUserSession(jedis, userId);
            if (oldest == null) break;
            log.warn("[Redis-Cache] Per-user memory limit exceeded: user={}, bytes={}MB > {}MB, evicting {}",
                    userId, userBytes / (1024 * 1024), maxMemoryBytesPerUser / (1024 * 1024), oldest);
            evictSessionInternal(jedis, oldest, true);
            userBytesStr = jedis.get(userBytesKey(userId));
            userBytes = userBytesStr != null ? Long.parseLong(userBytesStr) : 0;
        }
    }

    private void enforceGlobalMemoryLimit(Jedis jedis) {
        String globalBytesStr = jedis.get(globalBytesKey());
        long globalBytes = globalBytesStr != null ? Long.parseLong(globalBytesStr) : 0;
        long targetBytes = (long) (globalMaxMemoryBytes * evictionTargetRatio);

        if (globalBytes <= globalMaxMemoryBytes) return;

        log.warn("[Redis-Cache] Global memory limit exceeded: {}MB > {}MB, evicting to {}MB",
                globalBytes / (1024 * 1024), globalMaxMemoryBytes / (1024 * 1024), targetBytes / (1024 * 1024));

        while (globalBytes > targetBytes) {
            // Get globally oldest session
            Set<String> oldest = new HashSet<>(jedis.zrange(accessKey(), 0, 0));
            if (oldest.isEmpty()) break;
            String sid = oldest.iterator().next();
            evictSessionInternal(jedis, sid, true);

            globalBytesStr = jedis.get(globalBytesKey());
            globalBytes = globalBytesStr != null ? Long.parseLong(globalBytesStr) : 0;
        }

        log.info("[Redis-Cache] After global eviction: globalMB={}", globalBytes / (1024 * 1024));
    }

    /**
     * Find the oldest session for a user by checking access scores.
     */
    private String findOldestUserSession(Jedis jedis, String userId) {
        Set<String> sessions = jedis.smembers(userSessionsKey(userId));
        if (sessions.isEmpty()) return null;

        String oldest = null;
        double oldestScore = Double.MAX_VALUE;

        for (String sid : sessions) {
            Double score = jedis.zscore(accessKey(), sid);
            if (score != null && score < oldestScore) {
                oldestScore = score;
                oldest = sid;
            }
        }
        return oldest;
    }

    // ========== Key helpers ==========

    private String msgKey(String sessionId) {
        return prefix + ":msg:" + sessionId;
    }

    private String metaKey(String sessionId) {
        return prefix + ":meta:" + sessionId;
    }

    private String userSessionsKey(String userId) {
        return prefix + ":user_sessions:" + userId;
    }

    private String accessKey() {
        return prefix + ":access";
    }

    private String userBytesKey(String userId) {
        return prefix + ":user_bytes:" + userId;
    }

    private String globalBytesKey() {
        return prefix + ":global_bytes";
    }
}
