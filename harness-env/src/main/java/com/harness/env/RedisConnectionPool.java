package com.harness.env;

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
    public static void init() {
        EnvConfig cfg = EnvConfig.get();
        String redisUrl = cfg.getString(EnvKey.MEMORY_REDIS_URL, "redis://localhost:6379");
        String password = cfg.getString(EnvKey.MEMORY_REDIS_PASSWORD, "");
        int db = cfg.getInt(EnvKey.MEMORY_REDIS_DB, 0);

        URI uri = URI.create(redisUrl);
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        if (password != null && !password.isBlank()) {
            pool = new JedisPool(poolConfig, host, port, 5000, password, db);
        } else {
            pool = new JedisPool(poolConfig, host, port, 5000, null, db);
        }

        log.info("[Redis] Jedis pool initialized: host={}, port={}, db={}", host, port, db);
    }

    public static void shutdown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            log.info("[Redis] Jedis pool shut down");
        }
    }
}
