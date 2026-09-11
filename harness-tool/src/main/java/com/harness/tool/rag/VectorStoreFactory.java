package com.harness.tool.rag;

import com.harness.provider.EmbeddingModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工厂类，根据 HARNESS_RAG_PROVIDER 初始化连接池 + collection，创建 VectorStore。
 *
 * 职责分离：
 * - ConnectionPool：数据库连接（启动时主动建立）
 * - CollectionInitializer：schema/索引管理（Milvus）
 * - VectorStore：纯检索操作
 */
public final class VectorStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFactory.class);

    private VectorStoreFactory() {}

    public static VectorStore create(EmbeddingModelProvider embeddingProvider) {
        EnvConfig cfg = EnvConfig.get();
        String provider = cfg.getString(EnvKey.RAG_PROVIDER, "milvus");
        String database = cfg.getString(EnvKey.RAG_DATABASE, "default");
        String collection = cfg.getString(EnvKey.RAG_COLLECTION, "knowledge_documents");
        log.info("[VectorStore] Initializing provider={}, database={}, collection={}",
                provider, database, collection);

        return switch (provider.toLowerCase()) {
            case "milvus" -> {
                MilvusConnectionPool.init();
                MilvusCollectionInitializer.ensureCollection(
                        requireEmbeddingDimension(embeddingProvider));
                yield new MilvusVectorStore(embeddingProvider);
            }
            case "none" -> {
                log.info("[VectorStore] RAG disabled (provider=none)");
                yield null;
            }
            default -> throw new IllegalArgumentException("Unknown RAG provider: " + provider);
        };
    }

    private static int requireEmbeddingDimension(EmbeddingModelProvider provider) {
        int dimension = provider != null ? provider.dimension() : 0;
        if (dimension <= 0) {
            throw new IllegalStateException(
                    "An available embedding model with a positive dimension is required");
        }
        return dimension;
    }
}
