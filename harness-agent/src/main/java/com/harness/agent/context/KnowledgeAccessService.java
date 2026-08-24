package com.harness.agent.context;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.KnowledgeRequestContext;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.provider.RerankModelProvider;
import com.harness.tool.rag.RagRetriever;

import java.util.List;

/** Shared request-scoped authorization and storage access for knowledge tools. */
public final class KnowledgeAccessService {

    private static final ThreadLocal<RuntimeContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final ContextBuilder contextBuilder;
    private final int contextWindowMax;

    public KnowledgeAccessService(
            RerankModelProvider rerankModelProvider,
            EmbeddingModelProvider embeddingModelProvider
    ) {
        this(
                new ContextBuilder(rerankModelProvider, embeddingModelProvider),
                EnvConfig.get().getInt(EnvKey.RAG_CONTEXT_WINDOW_MAX, 2));
    }

    public KnowledgeAccessService(ContextBuilder contextBuilder, int contextWindowMax) {
        this.contextBuilder = java.util.Objects.requireNonNull(contextBuilder, "contextBuilder");
        if (contextWindowMax < 0) {
            throw new IllegalArgumentException("contextWindowMax cannot be negative");
        }
        this.contextWindowMax = contextWindowMax;
    }

    public ContextBuilder.ContextResult search(String query, String collection, int limit) {
        return contextBuilder.buildRagForTool(query, collection, limit);
    }

    public ContextBuilder.ContextResult searchWithQueries(
            List<String> queries,
            String collection,
            int limit
    ) {
        return contextBuilder.buildRagWithQueries(queries, collection, limit);
    }

    public List<RagRetriever.RagDocument> readContext(
            String collection,
            String documentId,
            int anchorChunkIndex,
            int before,
            int after
    ) {
        return contextBuilder.readContext(
                collection, documentId, anchorChunkIndex, before, after);
    }

    public int maxSearchLimit() {
        return contextBuilder.maxSearchLimit();
    }

    public int contextWindowMax() {
        return contextWindowMax;
    }

    public String effectiveCollection(String requestedCollection) {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        if (runtimeContext != null && runtimeContext.requestContext() != null) {
            return runtimeContext.requestContext().collection();
        }
        return requestedCollection == null
                ? contextBuilder.defaultCollection()
                : requestedCollection;
    }

    public boolean hasTrustedCollection() {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        return runtimeContext != null && runtimeContext.requestContext() != null;
    }

    public void requireAuthorizedDocument(String toolName, String documentId) {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        KnowledgeRequestContext requestContext = runtimeContext == null
                ? null
                : runtimeContext.requestContext();
        if (requestContext != null && !requestContext.allowsDocument(documentId)) {
            throw new ToolExecutionException(
                    toolName, "documentId is outside the trusted knowledge request scope");
        }
    }

    public static void setCurrentContext(
            String tenantId,
            KnowledgeRequestContext requestContext
    ) {
        CURRENT_CONTEXT.set(new RuntimeContext(tenantId, requestContext));
    }

    public static ContextSnapshot captureCurrentContext() {
        RuntimeContext runtimeContext = CURRENT_CONTEXT.get();
        return runtimeContext == null
                ? null
                : new ContextSnapshot(runtimeContext.tenantId(), runtimeContext.requestContext());
    }

    public static void restoreCurrentContext(ContextSnapshot snapshot) {
        if (snapshot == null) {
            CURRENT_CONTEXT.remove();
            return;
        }
        setCurrentContext(snapshot.tenantId(), snapshot.requestContext());
    }

    public static void clearCurrentContext() {
        CURRENT_CONTEXT.remove();
    }

    private record RuntimeContext(String tenantId, KnowledgeRequestContext requestContext) {
    }

    public record ContextSnapshot(String tenantId, KnowledgeRequestContext requestContext) {
    }
}
