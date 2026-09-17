package com.harness.tool.knowledge.index;

import com.harness.core.model.PageResponse;

import java.util.List;

/**
 * Searchable knowledge representation layer (Milvus), including bodies and historical versions.
 * MySQL is the authoritative control layer for permissions, lifecycle, current version and final route.
 * Persisted content here is not a disposable index that can always be rebuilt from MySQL.
 */
public interface KnowledgeProjectionStore {

    void initialize(KnowledgeProjectionCollections collections, int embeddingDimension);

    void upsert(List<KnowledgeProjection> projections);

    void upsertCatalog(List<KnowledgeProjection> projections);

    java.util.Optional<KnowledgeProjection> findMemory(
            com.harness.core.knowledge.KnowledgeConceptType type,
            String conceptId, String revisionId);

    java.util.Optional<com.harness.tool.knowledge.authority.KnowledgeRevisionSnapshot> findRevisionSnapshot(String revisionId);

    void activateRevision(String conceptId, String revisionId);

    void deleteRevision(String revisionId);

    void deleteConcept(String conceptId);

    PageResponse<KnowledgeProjectionIdentity> findIdentityPage(
            String afterRevisionId,
            int limit);

    KnowledgeProjectionSearchOutcome searchHybrid(KnowledgeProjectionSearch search);

    String providerName();
}
