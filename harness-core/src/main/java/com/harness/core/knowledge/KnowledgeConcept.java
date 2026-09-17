package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeConcept(
        String id,
        String tenantId,
        String userId,
        KnowledgeNamespaceType namespaceType,
        String namespaceKey,
        KnowledgeConceptType conceptType,
        String logicalKey,
        KnowledgeStatus status,
        String currentRevisionId,
        long version,
        Instant staleAfter,
        Instant createdAt,
        Instant updatedAt,
        // When the recorded event happened. Only USER_EPISODE carries one; null elsewhere.
        Instant eventTime
) {
    /** Concept without an event time — every kind except User Episode. */
    public KnowledgeConcept(
            String id,
            String tenantId,
            String userId,
            KnowledgeNamespaceType namespaceType,
            String namespaceKey,
            KnowledgeConceptType conceptType,
            String logicalKey,
            KnowledgeStatus status,
            String currentRevisionId,
            long version,
            Instant staleAfter,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, tenantId, userId, namespaceType, namespaceKey, conceptType, logicalKey, status,
                currentRevisionId, version, staleAfter, createdAt, updatedAt, null);
    }

    public KnowledgeConcept {
        id = KnowledgeModelSupport.requiredText(id, "id", 64);
        tenantId = KnowledgeModelSupport.optionalText(tenantId, "tenantId", 128);
        userId = KnowledgeModelSupport.optionalText(userId, "userId", 128);
        namespaceType = Objects.requireNonNull(namespaceType, "namespaceType");
        namespaceKey = KnowledgeModelSupport.optionalText(namespaceKey, "namespaceKey", 256);
        conceptType = Objects.requireNonNull(conceptType, "conceptType");
        logicalKey = KnowledgeModelSupport.optionalText(logicalKey, "logicalKey", 256);
        status = Objects.requireNonNull(status, "status");
        currentRevisionId = KnowledgeModelSupport.optionalText(currentRevisionId, "currentRevisionId", 64);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");

        if (conceptType.isUserOwned() && userId == null) {
            throw new IllegalArgumentException(conceptType.displayName() + " requires userId");
        }
        if (eventTime != null && conceptType != KnowledgeConceptType.USER_EPISODE) {
            throw new IllegalArgumentException(
                    "Only User Episode carries an event time, not " + conceptType.displayName());
        }
        if (conceptType == KnowledgeConceptType.OPERATION_PLAYBOOK && userId != null) {
            throw new IllegalArgumentException("Operation Playbook must not have userId");
        }
        if (conceptType == KnowledgeConceptType.USER_PREFERENCE && logicalKey == null) {
            throw new IllegalArgumentException("User Preference requires logicalKey");
        }
        if ((namespaceType == KnowledgeNamespaceType.COLLECTION || namespaceType == KnowledgeNamespaceType.GRAPH)
                && namespaceKey == null) {
            throw new IllegalArgumentException(namespaceType + " requires namespaceKey");
        }
    }

    public boolean isStaleAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return staleAfter != null && !instant.isBefore(staleAfter);
    }
}
