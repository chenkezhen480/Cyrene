package com.harness.env;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milvus 客户端连接池（单例）。
 * 启动时主动连接，不走懒加载。
 *
 * 配置：
 *   HARNESS_RAG_URL      - Milvus 地址（默认 http://localhost:19530）
 *   HARNESS_RAG_API_KEY  - token 认证（可选）
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

        ConnectConfig config = ConnectConfig.builder()
                .uri(url)
                .token(apiKey != null ? apiKey : "")
                .build();

        client = new MilvusClientV2(config);
        log.info("[DB] Milvus v2 client initialized: url={}", url);
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
