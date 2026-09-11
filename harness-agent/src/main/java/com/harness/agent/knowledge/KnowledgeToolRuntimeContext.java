package com.harness.agent.knowledge;

import com.harness.core.model.GraphRequestContext;
import com.harness.core.model.KnowledgeRequestContext;
import com.harness.tool.RunToolCatalog;

import java.util.Set;

/** Trusted request scope used by the unified knowledge tools. */
public record KnowledgeToolRuntimeContext(
        String tenantId,
        String userId,
        KnowledgeRequestContext knowledgeRequestContext,
        GraphRequestContext graphRequestContext,
        Set<String> authorizedTools
) {
    private static final ThreadLocal<KnowledgeToolRuntimeContext> CURRENT = new ThreadLocal<>();

    public KnowledgeToolRuntimeContext {
        tenantId = optional(tenantId);
        userId = optional(userId);
        authorizedTools = Set.copyOf(authorizedTools == null ? Set.of() : authorizedTools);
    }

    public static void activate(
            String tenantId,
            String userId,
            KnowledgeRequestContext knowledgeRequestContext,
            GraphRequestContext graphRequestContext,
            RunToolCatalog catalog
    ) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog is required");
        }
        CURRENT.set(new KnowledgeToolRuntimeContext(
                tenantId,
                userId,
                knowledgeRequestContext,
                graphRequestContext,
                catalog.getAll().stream().map(spec -> spec.name()).collect(
                        java.util.stream.Collectors.toUnmodifiableSet())));
    }

    public static KnowledgeToolRuntimeContext requireCurrent(String toolName) {
        KnowledgeToolRuntimeContext context = CURRENT.get();
        if (context == null) {
            throw new com.harness.core.exception.ToolExecutionException(
                    toolName, "No trusted knowledge runtime context is available");
        }
        return context;
    }

    public static KnowledgeToolRuntimeContext captureCurrent() {
        return CURRENT.get();
    }

    public static void restoreForCatalog(
            KnowledgeToolRuntimeContext snapshot,
            RunToolCatalog catalog
    ) {
        if (snapshot == null) {
            CURRENT.remove();
            return;
        }
        activate(
                snapshot.tenantId(),
                snapshot.userId(),
                snapshot.knowledgeRequestContext(),
                snapshot.graphRequestContext(),
                catalog);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public boolean allowsCollection(String collection) {
        return knowledgeRequestContext == null
                || knowledgeRequestContext.collection().equals(collection);
    }

    public boolean allowsDocument(String documentId) {
        return knowledgeRequestContext == null
                || knowledgeRequestContext.allowsDocument(documentId);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
