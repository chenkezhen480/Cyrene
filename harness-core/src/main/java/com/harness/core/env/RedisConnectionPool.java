package com.harness.core.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.URI;

/**
 * Shared Jedis connection pool for Redis-backed session cache.
 * Singleton — one pool per JVM, reused across all modules.
 */
public class RedisConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionPool.class);
    private static volatile JedisPool pool;

    private RedisConnectionPool() {}

    public static Jedis getConnection() {
        if (pool == null) {
            synchronized (RedisConnectionPool.class) {
                if (pool == null) {
                    init();
                }
            }
        }
        return pool.getResource();
    }

    /** 启动时调用，主动建立连接池 */
    public static synchronized void init() {
        if (pool != null) return;
        EnvConfig cfg = EnvConfig.get();
        String redisUrl = cfg.getString(EnvKey.MEMORY_REDIS_URL, "redis://localhost:6379");
        String password = cfg.getString(EnvKey.MEMORY_REDIS_PASSWORD, "");

        JedisPool candidate = null;
        try {
            int db = cfg.getInt(EnvKey.MEMORY_REDIS_DB, 0);
            URI uri = URI.create(redisUrl);
            String host = uri.getHost();
            if (host == null) throw new IllegalArgumentException("Redis URL must contain a host");
            int port = uri.getPort() > 0 ? uri.getPort() : 6379;

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);

            candidate = new JedisPool(poolConfig, host, port, 5000,
                    password != null && !password.isBlank() ? password : null, db);
            try (Jedis connection = candidate.getResource()) {
                connection.ping();
            }
            pool = candidate;
            log.info("[Redis] Jedis pool initialized: host={}, port={}, db={}", host, port, db);
        } catch (RuntimeException failure) {
            if (candidate != null) {
                try { candidate.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException(MiddlewareConnectionDiagnostics.failureMessage(
                    MiddlewareConnectionDiagnostics.Service.REDIS, redisUrl, failure), failure);
        }
    }

    public static synchronized void shutdown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("[Redis] Jedis pool shut down");
        }
        pool = null;
    }
}
