package com.harness.tool.knowledge.index;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.rag.MilvusCollectionInitializer;
import com.harness.tool.rag.MilvusConnectionPool;
import com.harness.tool.rag.MilvusVectorStore;
import com.harness.tool.rag.VectorStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.QueryReq;

import java.util.List;
import java.util.function.Supplier;

/** Keeps vector access closed until all four collections and the model are ready. */
public final class KnowledgeVectorRuntime {
    private final boolean enabled;
    private final KnowledgeProjectionCollections collections;
    private final Supplier<MilvusClientV2> connection;
    private final VectorStore documents;
    private final KnowledgeProjectionStore projections;
    private volatile Status status;

    public KnowledgeVectorRuntime(EmbeddingModelProvider embedding) {
        this(embedding, configuredCollections(), () -> {
            MilvusConnectionPool.init();
            return MilvusConnectionPool.getClient();
        });
    }

    KnowledgeVectorRuntime(EmbeddingModelProvider embedding,
                           KnowledgeProjectionCollections collections,
                           Supplier<MilvusClientV2> connection) {
        String provider = EnvConfig.get().getString(EnvKey.RAG_PROVIDER, "milvus");
        if (!"milvus".equalsIgnoreCase(provider) && !"none".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("Unknown RAG provider: " + provider);
        }
        this.enabled = !"none".equalsIgnoreCase(provider);
        this.collections = collections;
        this.connection = connection;
        this.status = enabled ? new Status("pending", "Configure the embedding model to enable knowledge retrieval.")
                : new Status("disabled", "Knowledge retrieval is disabled.");
        this.documents = enabled ? new MilvusVectorStore(embedding, this::readyClient) : null;
        this.projections = enabled ? new MilvusKnowledgeProjectionStore(this::readyClient, collections) : null;
    }

    private static KnowledgeProjectionCollections configuredCollections() {
        EnvConfig config = EnvConfig.get();
        return new KnowledgeProjectionCollections(
                config.getString(EnvKey.RAG_COLLECTION, "knowledge_documents"),
                config.getString(EnvKey.KNOWLEDGE_CATALOG_COLLECTION, "cyrene_knowledge_catalog"),
                config.getString(EnvKey.MEMORY_USER_KNOWLEDGE_COLLECTION, "cyrene_user_knowledge"),
                config.getString(EnvKey.MEMORY_OPERATION_KNOWLEDGE_COLLECTION, "cyrene_operation_knowledge"));
    }

    /** Prepare before publishing model settings; no reads or workers are enabled yet. */
    public Runnable prepare(EmbeddingModelProvider embedding, boolean identityChanged) {
        if (!enabled) return () -> {};
        try {
            if (!embedding.isAvailable() || embedding.dimension() <= 0) {
                throw new IllegalArgumentException("An embedding model with a positive dimension is required.");
            }
            var probe = embedding.embed("Embedding dimension validation");
            int actualDimension = probe == null ? 0 : probe.vector().length;
            if (actualDimension != embedding.dimension()) {
                throw new IllegalArgumentException("Embedding dimension mismatch: configured="
                        + embedding.dimension() + ", actual=" + actualDimension);
            }
            MilvusClientV2 client = connection.get();
            // Validate every existing collection before creating any missing collection.
            for (String collection : allCollections()) {
                if (!client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) continue;
                MilvusCollectionInitializer.validateDimension(client, collection, actualDimension);
            }
            MilvusCollectionInitializer.ensureCollection(client, collections.documentCollection(), actualDimension);
            new MilvusKnowledgeProjectionInitializer(client).initialize(collections, actualDimension);
            if (identityChanged) {
                for (String collection : allCollections()) {
                    if (!client.query(QueryReq.builder().collectionName(collection)
                            .consistencyLevel(ConsistencyLevel.STRONG)
                            .outputFields(List.of("id")).limit(1L).build()).getQueryResults().isEmpty()) {
                        throw new IllegalArgumentException("Cannot change the embedding model while collection '"
                                + collection + "' contains data; re-index existing knowledge first.");
                    }
                }
            }
            return () -> status = new Status("ready", "");
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            if (!isReady()) status = new Status("failed", message);
            throw new IllegalStateException(message, failure);
        }
    }

    private List<String> allCollections() {
        return List.of(collections.documentCollection(), collections.catalogCollection(),
                collections.userKnowledgeCollection(), collections.operationKnowledgeCollection());
    }

    private MilvusClientV2 readyClient() {
        requireReady();
        return connection.get();
    }

    public void requireReady() {
        Status current = status;
        if (!"ready".equals(current.state())) throw new IllegalStateException(current.message());
    }

    public boolean isReady() { return "ready".equals(status.state()); }
    public boolean enabled() { return enabled; }
    public Status status() { return status; }
    public VectorStore documents() { return documents; }
    public KnowledgeProjectionStore projections() { return projections; }
    public record Status(String state, String message) {}
}
