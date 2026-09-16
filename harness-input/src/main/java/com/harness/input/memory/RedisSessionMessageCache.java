package com.harness.input.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.model.MemoryMessage;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.RedisConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Redis-backed session message cache with native sliding TTL.
 *
 * <p>The message store is the single source of truth: this cache only makes reloading a
 * session's history cheaper. Every read and write refreshes the key's TTL, so an actively used
 * session stays resident and an idle one disappears on its own — Redis is the only thing that
 * removes entries. There is no LRU, no per-user quota and no memory accounting; memory pressure
 * is Redis' own concern ({@code maxmemory} / {@code maxmemory-policy}).
 *
 * <p>A session is stored as a Redis list, one message per element, rather than one JSON array.
 * Appending is then a native {@code RPUSH} guarded by a Lua script instead of a
 * read-modify-write over the whole history: two threads appending to the same session can no
 * longer lose one another's message, and a reader can never observe a partially written
 * history. Both matter here — a lost or truncated element leaves an assistant tool call without
 * its results, which every OpenAI-compatible provider rejects.
 *
 * <p>All operations are best-effort: on Redis failure the cache degrades to a miss and the
 * caller reloads from the store.
 */
public class RedisSessionMessageCache implements SessionMessageCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionMessageCache.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Appends only when the key already exists, and refreshes its TTL. Atomic: the existence
     * check and the write cannot be split, so a concurrent rebuild cannot be appended into and
     * a missing entry is never resurrected as a one-message tail.
     */
    private static final String APPEND_IF_PRESENT_SCRIPT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then\n"
                    + "  redis.call('RPUSH', KEYS[1], ARGV[1])\n"
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[2])\n"
                    + "  return 1\n"
                    + "end\n"
                    + "return 0";

    private final String prefix;
    private final int ttlSeconds;
    private final SessionCacheMetrics metrics = new SessionCacheMetrics("redis");

    private Consumer<String> onEvict;

    public RedisSessionMessageCache() {
        EnvConfig cfg = EnvConfig.get();
        this.prefix = cfg.getString(EnvKey.MEMORY_REDIS_KEY_PREFIX, "harness");
        this.ttlSeconds = cfg.getInt(EnvKey.MEMORY_REDIS_TTL_MINUTES, 720) * 60;
        log.info("[Cache] RedisSessionMessageCache initialized: prefix={}, ttl={}s", prefix, ttlSeconds);
    }

    @Override
    public void setOnEvict(Consumer<String> onEvict) {
        this.onEvict = onEvict;
    }

    @Override
    public SessionCacheLookup lookup(String sessionId) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            List<String> elements = jedis.lrange(msgKey(sessionId), 0, -1);
            if (elements.isEmpty()) {
                return SessionCacheLookup.miss();
            }
            // Sliding TTL: a read is a use, so keep the entry alive.
            jedis.expire(msgKey(sessionId), ttlSeconds);
            List<MemoryMessage> messages = new ArrayList<>(elements.size());
            for (String element : elements) {
                messages.add(MAPPER.readValue(element, MemoryMessage.class));
            }
            return SessionCacheLookup.hit(messages);
        } catch (Exception e) {
            log.warn("[Cache] lookup failed for session {}: {}", sessionId, e.getMessage());
            return SessionCacheLookup.error();
        }
    }

    @Override
    public List<MemoryMessage> getIfPresent(String sessionId) {
        return lookup(sessionId).messages();
    }

    @Override
    public void put(String sessionId, String userId, List<MemoryMessage> messages) {
        putObserved(sessionId, userId, messages);
    }

    @Override
    public boolean putObserved(String sessionId, String userId, List<MemoryMessage> messages) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            String[] elements = new String[messages.size()];
            for (int i = 0; i < messages.size(); i++) {
                elements[i] = MAPPER.writeValueAsString(messages.get(i));
            }
            // Replace-then-fill must not be observable halfway: a reader that saw the deletion
            // without the refill would rebuild the entry from the store, and one that saw a
            // partial fill would cache a truncated history.
            Transaction transaction = jedis.multi();
            transaction.del(msgKey(sessionId));
            if (elements.length > 0) {
                transaction.rpush(msgKey(sessionId), elements);
                transaction.expire(msgKey(sessionId), ttlSeconds);
            }
            transaction.exec();
            return elements.length > 0;
        } catch (Exception e) {
            log.warn("[Cache] put failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public void appendIfPresent(String sessionId, String userId, MemoryMessage message) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            // A miss must stay a miss. Rebuilding the entry from this one message would publish
            // a history that starts mid-conversation — for a Tool round that means tool results
            // with no declaring assistant message, which every OpenAI-compatible provider
            // rejects with "Messages with role 'tool' must be a response to a preceding message
            // with 'tool_calls'". Leaving the key absent lets the next lookup() reload the full
            // history from the store.
            jedis.eval(
                    APPEND_IF_PRESENT_SCRIPT,
                    Collections.singletonList(msgKey(sessionId)),
                    Arrays.asList(MAPPER.writeValueAsString(message), String.valueOf(ttlSeconds)));
        } catch (Exception e) {
            log.warn("[Cache] append failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void remove(String sessionId) {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            if (jedis.del(msgKey(sessionId)) == 0) {
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
        } catch (Exception e) {
            log.warn("[Cache] remove failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public int size() {
        try (Jedis jedis = RedisConnectionPool.getConnection()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(msgKey("*")).count(200);
            int count = 0;
            do {
                ScanResult<String> page = jedis.scan(cursor, params);
                count += page.getResult().size();
                cursor = page.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            return count;
        } catch (Exception e) {
            log.warn("[Cache] size failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int evictExpired() {
        // Redis expires keys on its own; nothing to sweep.
        return 0;
    }

    @Override
    public SessionCacheMetrics metrics() {
        return metrics;
    }

    private String msgKey(String sessionId) {
        return prefix + ":msg:" + sessionId;
    }
}
