package com.harness.tool.rag;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.env.MiddlewareConnectionDiagnostics;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.RetryConfig;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.database.request.DescribeDatabaseReq;
import io.milvus.v2.service.database.response.ListDatabasesResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

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

        MilvusClientV2 candidate = null;
        try {
            long startupTimeoutMs = startupTimeoutMs();
            // 确保目标数据库存在（先连 default 库创建，再切过去）。
            if (!"default".equalsIgnoreCase(database)) ensureDatabase(url, apiKey, database, startupTimeoutMs);
            ConnectConfig config = connectConfig(url, apiKey, database);
            candidate = new MilvusClientV2(config);
            long requestDeadlineMs = config.getRpcDeadlineMs();
            candidate.withTimeout(startupTimeoutMs, TimeUnit.MILLISECONDS);
            candidate.retryConfig(RetryConfig.builder().maxRetryTimes(1).build());
            candidate.describeDatabase(DescribeDatabaseReq.builder().databaseName(database).build());
            candidate.withTimeout(requestDeadlineMs, TimeUnit.MILLISECONDS);
            candidate.retryConfig(RetryConfig.builder().build());
            client = candidate;
            log.info("[Milvus] Milvus v2 client initialized: endpoint={}, db={}",
                    MiddlewareConnectionDiagnostics.endpoint(url), database);
        } catch (RuntimeException failure) {
            if (candidate != null) {
                try { candidate.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException(MiddlewareConnectionDiagnostics.failureMessage(
                    MiddlewareConnectionDiagnostics.Service.MILVUS, url, failure), failure);
        }
    }

    private static ConnectConfig connectConfig(String url, String apiKey, String database) {
        EnvConfig cfg = EnvConfig.get();
        long connectTimeoutMs = cfg.getLong(EnvKey.RAG_CONNECT_TIMEOUT_MS, 10_000);
        if (connectTimeoutMs <= 0) throw new IllegalArgumentException(EnvKey.RAG_CONNECT_TIMEOUT_MS + " must be positive");
        return ConnectConfig.builder().uri(url).token(apiKey != null ? apiKey : "").dbName(database)
                .connectTimeoutMs(connectTimeoutMs).build();
    }

    private static long startupTimeoutMs() {
        long timeoutMs = EnvConfig.get().getLong(EnvKey.RAG_STARTUP_TIMEOUT_MS, 10_000);
        if (timeoutMs <= 0) throw new IllegalArgumentException(EnvKey.RAG_STARTUP_TIMEOUT_MS + " must be positive");
        return timeoutMs;
    }

    /**
     * 连接 default 库，确保目标数据库存在
     */
    private static void ensureDatabase(String url, String apiKey, String database, long startupTimeoutMs) {
        MilvusClientV2 defaultClient = new MilvusClientV2(connectConfig(url, apiKey, "default"));
        try {
            defaultClient.withTimeout(startupTimeoutMs, TimeUnit.MILLISECONDS);
            defaultClient.retryConfig(RetryConfig.builder().maxRetryTimes(1).build());
            ListDatabasesResp databases = defaultClient.listDatabases();
            if (databases.getDatabaseNames().contains(database)) {
                log.debug("[Milvus] Milvus database '{}' already exists", database);
                return;
            }
            defaultClient.createDatabase(CreateDatabaseReq.builder()
                    .databaseName(database).build());
            log.info("[Milvus] Milvus database '{}' created", database);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exist")) {
                log.debug("[Milvus] Milvus database '{}' already exists", database);
            } else {
                throw e;
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

    public static synchronized void shutdown() {
        if (client != null) {
            try {
                client.close();
                log.info("[Milvus] Milvus client shut down");
            } catch (Exception e) {
                log.warn("[Milvus] Failed to close Milvus client: {}", e.getMessage());
            }
            client = null;
        }
    }
}
