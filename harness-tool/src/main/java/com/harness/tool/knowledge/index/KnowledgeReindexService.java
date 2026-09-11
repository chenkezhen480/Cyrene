package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptCursor;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import com.harness.tool.knowledge.authority.KnowledgeRepository;


/** Explicit, stable-cursor reindex and orphan reconciliation service. */
public final class KnowledgeReindexService {

    private final KnowledgeRepository repository;
    private final KnowledgeProjectionStore projectionStore;
    private final KnowledgeIndexProjector projector;

    public KnowledgeReindexService(
            KnowledgeRepository repository,
            KnowledgeProjectionStore projectionStore,
            KnowledgeProjectionMapper mapper
    ) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.projectionStore = java.util.Objects.requireNonNull(
                projectionStore, "projectionStore");
        this.projector = new KnowledgeIndexProjector(repository, projectionStore, mapper);
    }

    public ReindexPageResult reindexPage(
            ReindexScope scope,
            KnowledgeConceptCursor cursor,
            int limit
    ) {
        validateScope(scope);
        var page = repository.findPage(
                scope.tenantId(),
                scope.userId(),
                scope.namespaceType(),
                scope.conceptType(),
                null,
                cursor,
                limit);
        int upserted = 0;
        for (KnowledgeConcept concept : page.items()) {
            var operation = concept.status() == KnowledgeStatus.DEPRECATED
                    ? com.harness.core.knowledge.KnowledgeIndexOperation.DELETE_CONCEPT
                    : com.harness.core.knowledge.KnowledgeIndexOperation.UPSERT_CURRENT;
            if (concept.currentRevisionId() == null) continue;
            projector.project(new com.harness.core.knowledge.KnowledgeIndexTask(null, concept.id(), concept.currentRevisionId(),
                    operation, com.harness.core.knowledge.KnowledgeIndexTaskStatus.PENDING, 0,
                    java.time.Instant.now(), null, null, null, java.time.Instant.now()));
            if (operation == com.harness.core.knowledge.KnowledgeIndexOperation.UPSERT_CURRENT) upserted++;
        }
        KnowledgeConceptCursor next = null;
        if (page.pageInfo().hasMore() && !page.items().isEmpty()) {
            KnowledgeConcept last = page.items().getLast();
            next = new KnowledgeConceptCursor(last.updatedAt(), last.id());
        }
        return new ReindexPageResult(
                page.items().size(), upserted, next, page.pageInfo().hasMore());
    }

    public ReconcilePageResult reconcileProjectionPage(
            String afterRevisionId,
            int limit
    ) {
        var page = projectionStore.findIdentityPage(afterRevisionId, limit);
        int deleted = 0;
        for (KnowledgeProjectionIdentity identity : page.items()) {
            KnowledgeHead head = repository.findAuthorityById(identity.conceptId()).orElse(null);
            boolean current = head != null
                    && head.concept().status() != KnowledgeStatus.DEPRECATED
                    && KnowledgeProjectionMapper.isSearchableType(
                    head.concept().conceptType());
            if (!current) {
                projectionStore.deleteRevision(identity.revisionId());
                deleted++;
            }
        }
        String next = page.pageInfo().hasMore() && !page.items().isEmpty()
                ? page.items().getLast().revisionId()
                : null;
        return new ReconcilePageResult(
                page.items().size(), deleted, next, page.pageInfo().hasMore());
    }

    private void validateScope(ReindexScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        if (!KnowledgeProjectionMapper.isSearchableType(scope.conceptType())) {
            throw new IllegalArgumentException(
                    "Reindex scope Concept type is not searchable");
        }
    }

    public record ReindexScope(
            String tenantId,
            String userId,
            KnowledgeNamespaceType namespaceType,
            KnowledgeConceptType conceptType
    ) {
        public ReindexScope {
            if (namespaceType == null || conceptType == null) {
                throw new IllegalArgumentException(
                        "namespaceType and conceptType are required");
            }
            tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
            userId = userId == null || userId.isBlank() ? null : userId.trim();
        }
    }

    public record ReindexPageResult(
            int scanned,
            int upserted,
            KnowledgeConceptCursor nextCursor,
            boolean hasMore
    ) {
    }

    public record ReconcilePageResult(
            int scanned,
            int deleted,
            String nextCursor,
            boolean hasMore
    ) {
    }
}
