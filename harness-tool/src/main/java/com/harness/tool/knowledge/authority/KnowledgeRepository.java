package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptCursor;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeLink;
import com.harness.core.knowledge.KnowledgeLinkCursor;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeSource;
import com.harness.core.knowledge.KnowledgeSourceCursor;
import com.harness.core.knowledge.KnowledgeSourceType;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.KnowledgeVerification;
import com.harness.core.knowledge.KnowledgeVerificationCursor;
import com.harness.core.model.PageResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeRepository {

    default Optional<KnowledgeHead> findAuthorityById(String conceptId) { return findById(conceptId); }

    default Map<String, KnowledgeHead> findAuthorityByIds(List<String> ids) { return findByIds(ids); }

    default void withAuthorityLock(String conceptId, Runnable action) { action.run(); }

    void deleteDocumentContent(String conceptId);

    KnowledgeRevisionSnapshot findSnapshot(String revisionId);

    Optional<KnowledgeHead> findById(String conceptId);

    Map<String, KnowledgeHead> findByIds(List<String> conceptIds);

    PageResponse<KnowledgeConcept> findPage(
            String tenantId,
            String userId,
            KnowledgeNamespaceType namespaceType,
            KnowledgeConceptType conceptType,
            KnowledgeStatus status,
            KnowledgeConceptCursor cursor,
            int limit);

    PageResponse<KnowledgeConcept> findPageInNamespace(
            String tenantId,
            KnowledgeNamespaceType namespaceType,
            String namespaceKey,
            KnowledgeConceptType conceptType,
            KnowledgeStatus status,
            KnowledgeConceptCursor cursor,
            int limit);

    Optional<KnowledgeRevision> findRevisionById(String revisionId);

    PageResponse<KnowledgeRevision> findRevisionPage(
            String conceptId,
            Long beforeRevisionNumber,
            int limit);

    PageResponse<KnowledgeSource> findSourcePage(
            String revisionId,
            KnowledgeSourceCursor cursor,
            int limit);

    PageResponse<KnowledgeVerification> findVerificationPage(
            String revisionId,
            KnowledgeVerificationCursor cursor,
            int limit);

    PageResponse<KnowledgeLink> findOutgoingLinkPage(
            String fromConceptId,
            KnowledgeLinkCursor cursor,
            int limit);

    PageResponse<KnowledgeLink> findIncomingLinkPage(
            String toConceptId,
            KnowledgeLinkCursor cursor,
            int limit);

    boolean isSourceReferenced(KnowledgeSourceType sourceType, String sourceId);

    void commitChanges(List<KnowledgeRevisionChange> changes);
}
