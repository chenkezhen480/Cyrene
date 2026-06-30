package com.harness.env;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Shared HikariCP connection pool for all MySQL stores.
 * Singleton — one pool per JVM, reused across all modules.
 */
public class MysqlConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(MysqlConnectionPool.class);
    private static volatile HikariDataSource dataSource;

    private MysqlConnectionPool() {}

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            synchronized (MysqlConnectionPool.class) {
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
        String dbUrl = cfg.getString(EnvKey.AUDIT_DB_URL, "jdbc:mysql://localhost:3306/agent");
        String dbUser = cfg.getString(EnvKey.AUDIT_DB_USER, "root");
        String dbPass = cfg.getString(EnvKey.AUDIT_DB_PASS, "1234");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(dbUrl);
        hikariConfig.setUsername(dbUser);
        hikariConfig.setPassword(dbPass);
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setIdleTimeout(300000);   // 5 min
        hikariConfig.setMaxLifetime(600000);    // 10 min
        hikariConfig.setPoolName("harness-mysql");

        dataSource = new HikariDataSource(hikariConfig);
        log.info("[DB] HikariCP pool initialized: url={}, maxPool={}", dbUrl, hikariConfig.getMaximumPoolSize());
    }

    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[DB] HikariCP pool shut down");
        }
    }
}
