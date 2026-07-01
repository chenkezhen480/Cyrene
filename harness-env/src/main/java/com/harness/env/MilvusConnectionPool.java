package com.harness.env;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milvus 客户端连接池（单例）。
 * 启动时主动连接，不走懒加载。
 *
 * 配置：
 *   HARNESS_RAG_URL      - Milvus 地址（默认 http://localhost:19530）
 *   HARNESS_RAG_API_KEY  - token 认证（可选）
 *   HARNESS_RAG_DATABASE - 数据库名（默认 default）
 */
public class MilvusConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(MilvusConnectionPool.class);
    private static volatile MilvusClientV2 client;

    private MilvusConnectionPool() {}

    /** 启动时调用，主动建立连接 */
    public static synchronized void init() {
        if (client != null) return;

        EnvConfig cfg = EnvConfig.get();
        String url = cfg.getString(EnvKey.RAG_URL, "http://localhost:19530");
        String apiKey = cfg.getString(EnvKey.RAG_API_KEY, "");
        String database = cfg.getString(EnvKey.RAG_DATABASE, "default");

        // 确保目标数据库存在（先连 default 库创建，再切过去）
        if (!"default".equalsIgnoreCase(database)) {
            ensureDatabase(url, apiKey, database);
        }

        ConnectConfig config = ConnectConfig.builder()
                .uri(url)
                .token(apiKey != null ? apiKey : "")
                .dbName(database)
                .build();

        client = new MilvusClientV2(config);
        log.info("[DB] Milvus v2 client initialized: url={}, db={}", url, database);
    }

    /**
     * 连接 default 库，确保目标数据库存在
     */
    private static void ensureDatabase(String url, String apiKey, String database) {
        ConnectConfig defaultConfig = ConnectConfig.builder()
                .uri(url)
                .token(apiKey != null ? apiKey : "")
                .dbName("default")
                .build();
        MilvusClientV2 defaultClient = new MilvusClientV2(defaultConfig);
        try {
            defaultClient.createDatabase(CreateDatabaseReq.builder()
                    .databaseName(database).build());
            log.info("[DB] Milvus database '{}' created", database);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("already exist")) {
                log.debug("[DB] Milvus database '{}' already exists", database);
            } else {
                log.warn("[DB] Failed to create database '{}': {}", database, e.getMessage());
            }
        } finally {
            defaultClient.close();
        }
    }

    public static MilvusClientV2 getClient() {
        if (client == null) {
            throw new IllegalStateException("MilvusConnectionPool not initialized. Call init() first.");
        }
        return client;
    }

    public static void shutdown() {
        if (client != null) {
            try {
                client.close();
                log.info("[DB] Milvus client shut down");
            } catch (Exception e) {
                log.warn("[DB] Failed to close Milvus client: {}", e.getMessage());
            }
            client = null;
        }
    }
}
