package com.harness.preprocess.rag;

import com.harness.ai.model.EmbeddingModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.env.PgConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工厂类，根据 HARNESS_RAG_PROVIDER 初始化连接池 + collection，创建 VectorStore。
 *
 * 职责分离：
 * - ConnectionPool：数据库连接（启动时主动建立）
 * - CollectionInitializer：schema/索引管理（Milvus 需要，PG 不需要）
 * - VectorStore：纯检索操作
 */
public final class VectorStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFactory.class);

    private VectorStoreFactory() {}

    public static VectorStore create(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        String provider = cfg.getString(EnvKey.RAG_PROVIDER, "pgvector");
        log.info("[VectorStore] Initializing provider: {}", provider);

        return switch (provider.toLowerCase()) {
            case "pgvector" -> {
                PgConnectionPool.init();
                yield new PgVectorStore(embeddingProvider);
            }
            case "milvus" -> {
                MilvusConnectionPool.init();
                MilvusCollectionInitializer.ensureCollection();
                yield new MilvusVectorStore(embeddingProvider);
            }
            case "none" -> {
                log.info("[VectorStore] RAG disabled (provider=none)");
                yield null;
            }
            default -> throw new IllegalArgumentException("Unknown RAG provider: " + provider);
        };
    }
}
