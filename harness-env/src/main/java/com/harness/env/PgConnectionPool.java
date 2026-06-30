package com.harness.env;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared HikariCP connection pool for PostgreSQL (pgvector RAG).
 * Singleton — one pool per JVM, reused across all modules.
 */
public class PgConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(PgConnectionPool.class);
    private static volatile HikariDataSource dataSource;

    private PgConnectionPool() {}

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            synchronized (PgConnectionPool.class) {
                if (dataSource == null) {
                    init();
                }
            }
        }
        return dataSource.getConnection();
    }

    /** 启动时调用，主动建立连接池 */
    public static void init() {
        EnvConfig cfg = EnvConfig.get();
        // 通用变量优先，PG 专用变量作为 fallback
        String dbUrl = cfg.getString(EnvKey.RAG_URL);
        if (dbUrl == null || dbUrl.trim().isEmpty()) {
            dbUrl = cfg.getString(EnvKey.RAG_PG_URL, "jdbc:postgresql://localhost:5432/agent");
        }
        String dbUser = cfg.getString(EnvKey.RAG_USER, cfg.getString(EnvKey.RAG_PG_USER, "postgres"));
        String dbPass = cfg.getString(EnvKey.RAG_PASS, cfg.getString(EnvKey.RAG_PG_PASS, ""));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(dbUrl);
        hikariConfig.setUsername(dbUser);
        hikariConfig.setPassword(dbPass);
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setIdleTimeout(300000);   // 5 min
        hikariConfig.setMaxLifetime(600000);    // 10 min
        hikariConfig.setPoolName("harness-pg");

        dataSource = new HikariDataSource(hikariConfig);
        log.info("[DB] HikariCP pg pool initialized: url={}, maxPool={}", dbUrl, hikariConfig.getMaximumPoolSize());
    }

    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[DB] HikariCP pg pool shut down");
        }
    }
}
