package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;
import com.harness.core.knowledge.KnowledgeRouteTarget;

import java.time.Instant;
import java.util.List;

/** Searchable knowledge representation carrying persisted version metadata; MySQL controls validity and routing. */
public record KnowledgeProjection(
        String id,
        String conceptId,
        String revisionId,
        String tenantId,
        String userId,
        KnowledgeNamespaceType namespaceType,
        String namespaceKey,
        KnowledgeConceptType conceptType,
        KnowledgeRouteTarget routeTarget,
        String resourceUri,
        String title,
        String description,
        String content,
        Instant generatedAt,
        Instant eventTime,
        String logicalKey,
        Double qualityScore,
        List<String> requiredTools,
        float[] embedding,
        String revisionData
) {
    public KnowledgeProjection(
        String id,
        String conceptId,
        String revisionId,
        String tenantId,
        String userId,
        KnowledgeNamespaceType namespaceType,
        String namespaceKey,
        KnowledgeConceptType conceptType,
        KnowledgeRouteTarget routeTarget,
        String resourceUri,
        String title,
        String description,
        String content,
        Instant generatedAt,
        Instant eventTime,
        String logicalKey,
        Double qualityScore,
        List<String> requiredTools,
        float[] embedding) {
        this(id, conceptId, revisionId, tenantId, userId, namespaceType, namespaceKey, conceptType, routeTarget, resourceUri, title, description, content, generatedAt, eventTime, logicalKey, qualityScore, requiredTools, embedding, null);
    }

    public KnowledgeProjection withRevisionData(String data) {
        return new KnowledgeProjection(id, conceptId, revisionId, tenantId, userId, namespaceType, namespaceKey, conceptType, routeTarget, resourceUri, title, description, content, generatedAt, eventTime, logicalKey, qualityScore, requiredTools, embedding, data);
    }

    public KnowledgeProjection {
        id = required(id, "id");
        conceptId = required(conceptId, "conceptId");
        revisionId = required(revisionId, "revisionId");
        if (!id.equals(revisionId)) {
            throw new IllegalArgumentException("projection id must equal revisionId");
        }
        tenantId = optional(tenantId);
        userId = optional(userId);
        namespaceType = java.util.Objects.requireNonNull(namespaceType, "namespaceType");
        namespaceKey = optional(namespaceKey);
        conceptType = java.util.Objects.requireNonNull(conceptType, "conceptType");
        if (conceptType == KnowledgeConceptType.USER_EPISODE && userId == null) {
            throw new IllegalArgumentException("User Episode projection requires userId");
        }
        if (conceptType != KnowledgeConceptType.USER_EPISODE && userId != null) {
            throw new IllegalArgumentException(
                    "Only User Episode projections may contain userId");
        }
        routeTarget = java.util.Objects.requireNonNull(routeTarget, "routeTarget");
        resourceUri = required(resourceUri, "resourceUri");
        title = required(title, "title");
        description = optional(description);
        content = required(content, "content");
        generatedAt = java.util.Objects.requireNonNull(generatedAt, "generatedAt");
        logicalKey = optional(logicalKey);
        requiredTools = List.copyOf(requiredTools == null ? List.of() : requiredTools);
        embedding = embedding == null ? null : embedding.clone();
        validateSearchableWiki(conceptType, routeTarget);
        if (embedding != null && conceptType == KnowledgeConceptType.USER_EPISODE && eventTime == null) {
            throw new IllegalArgumentException("User Episode projection requires eventTime");
        }
        if (embedding != null && conceptType == KnowledgeConceptType.OPERATION_PLAYBOOK
                && logicalKey == null) {
            throw new IllegalArgumentException(
                    "Operation Playbook projection requires logicalKey");
        }
    }

    @Override
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }

    private static void validateSearchableWiki(
            KnowledgeConceptType conceptType,
            KnowledgeRouteTarget routeTarget
    ) {
        if (!KnowledgeProjectionMapper.isSearchableType(conceptType)) {
            throw new IllegalArgumentException(
                    "Knowledge projection must be a searchable Wiki Concept");
        }
        if (routeTarget != KnowledgeProjectionMapper.routeTarget(conceptType)) {
            throw new IllegalArgumentException(
                    conceptType + " must route to "
                            + KnowledgeProjectionMapper.routeTarget(conceptType));
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
