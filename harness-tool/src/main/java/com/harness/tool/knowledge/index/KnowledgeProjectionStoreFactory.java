package com.harness.tool.knowledge.index;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.rag.MilvusConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Initializes only the configured projection provider and records the selected scope. */
public final class KnowledgeProjectionStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(
            KnowledgeProjectionStoreFactory.class);

    private KnowledgeProjectionStoreFactory() {
    }

    public static KnowledgeProjectionRuntime create(EmbeddingModelProvider embeddingProvider) {
        EnvConfig config = EnvConfig.get();
        String provider = config.getString(EnvKey.RAG_PROVIDER, "milvus").trim();
        String database = config.getString(EnvKey.RAG_DATABASE, "default").trim();
        KnowledgeProjectionCollections collections = new KnowledgeProjectionCollections(
                config.getString(EnvKey.RAG_COLLECTION, "knowledge_documents"),
                config.getString(
                        EnvKey.KNOWLEDGE_CATALOG_COLLECTION,
                        "cyrene_knowledge_catalog"),
                config.getString(
                        EnvKey.MEMORY_USER_KNOWLEDGE_COLLECTION,
                        "cyrene_user_knowledge"),
                config.getString(
                        EnvKey.MEMORY_OPERATION_KNOWLEDGE_COLLECTION,
                        "cyrene_operation_knowledge"));
        log.info("[KnowledgeProjection] provider={}, database={}, documentCollection={}, "
                        + "catalogCollection={}, userKnowledgeCollection={}, "
                        + "operationKnowledgeCollection={}",
                provider, database, collections.documentCollection(),
                collections.catalogCollection(), collections.userKnowledgeCollection(),
                collections.operationKnowledgeCollection());
        if ("none".equalsIgnoreCase(provider)) {
            return new KnowledgeProjectionRuntime(null, collections, database);
        }
        int dimension = requireDimension(embeddingProvider);
        KnowledgeProjectionStore store = switch (provider.toLowerCase()) {
            case "milvus" -> {
                MilvusKnowledgeProjectionStore milvus =
                        new MilvusKnowledgeProjectionStore(
                                MilvusConnectionPool.getClient());
                milvus.initialize(collections, dimension);
                yield milvus;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown RAG provider for Knowledge projection: " + provider);
        };
        return new KnowledgeProjectionRuntime(store, collections, database);
    }

    private static int requireDimension(EmbeddingModelProvider provider) {
        if (provider == null || !provider.isAvailable() || provider.dimension() <= 0) {
            throw new IllegalStateException(
                    "Knowledge projection requires an available embedding provider");
        }
        return provider.dimension();
    }

    public record KnowledgeProjectionRuntime(
            KnowledgeProjectionStore store,
            KnowledgeProjectionCollections collections,
            String database
    ) {
        public boolean enabled() {
            return store != null;
        }
    }
}
