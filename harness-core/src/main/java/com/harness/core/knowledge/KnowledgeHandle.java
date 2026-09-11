package com.harness.core.knowledge;

import java.util.Objects;

/**
 * Model-visible knowledge locator. Every field is untrusted and must be re-authorized on read.
 */
public record KnowledgeHandle(
        String protocolVersion,
        KnowledgeConceptType knowledgeKind,
        String conceptId,
        String revisionId,
        KnowledgeRouteTarget routeTarget,
        String collectionKey,
        String documentId,
        Integer chunkIndex
) {
    public static final String CURRENT_PROTOCOL_VERSION = "1";

    public KnowledgeHandle {
        protocolVersion = KnowledgeModelSupport.requiredText(protocolVersion, "protocolVersion", 8);
        if (!CURRENT_PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException("unsupported knowledge handle protocol version");
        }
        knowledgeKind = Objects.requireNonNull(knowledgeKind, "knowledgeKind");
        if (!isSearchableType(knowledgeKind)) {
            throw new IllegalArgumentException(
                    "knowledge handle supports only searchable Wiki Concepts");
        }
        conceptId = KnowledgeModelSupport.requiredText(conceptId, "conceptId", 64);
        revisionId = KnowledgeModelSupport.requiredText(revisionId, "revisionId", 64);
        routeTarget = Objects.requireNonNull(routeTarget, "routeTarget");
        collectionKey = KnowledgeModelSupport.optionalText(collectionKey, "collectionKey", 128);
        documentId = KnowledgeModelSupport.optionalText(documentId, "documentId", 64);
        if (chunkIndex != null && chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex must not be negative");
        }
        if (routeTarget == KnowledgeRouteTarget.DOCUMENT
                && (collectionKey == null || documentId == null)) {
            throw new IllegalArgumentException("document handle requires collectionKey and documentId");
        }
        if (routeTarget != KnowledgeRouteTarget.DOCUMENT
                && (collectionKey != null || documentId != null || chunkIndex != null)) {
            throw new IllegalArgumentException("non-document handle must not carry document locator fields");
        }
        KnowledgeRouteTarget requiredRoute = switch (knowledgeKind) {
            case SOURCE_DOCUMENT -> KnowledgeRouteTarget.DOCUMENT;
            case GRAPH_SCHEMA, GRAPH_SPACE -> KnowledgeRouteTarget.GRAPH;
            case USER_EPISODE -> KnowledgeRouteTarget.USER_MEMORY;
            case OPERATION_PLAYBOOK -> KnowledgeRouteTarget.OPERATION_MEMORY;
            case USER_PREFERENCE -> throw new IllegalArgumentException(
                    "User Preference does not support knowledge handles");
        };
        if (routeTarget != requiredRoute) {
            throw new IllegalArgumentException(
                    knowledgeKind + " must route to " + requiredRoute);
        }
    }

    public static KnowledgeHandle concept(
            KnowledgeConceptType knowledgeKind,
            String conceptId,
            String revisionId,
            KnowledgeRouteTarget routeTarget
    ) {
        if (routeTarget == KnowledgeRouteTarget.DOCUMENT) {
            throw new IllegalArgumentException("routeTarget requires a specialized handle factory");
        }
        return new KnowledgeHandle(
                CURRENT_PROTOCOL_VERSION,
                knowledgeKind,
                conceptId,
                revisionId,
                routeTarget,
                null,
                null,
                null);
    }

    public static KnowledgeHandle document(
            KnowledgeConceptType knowledgeKind,
            String conceptId,
            String revisionId,
            String collectionKey,
            String documentId,
            Integer chunkIndex
    ) {
        return new KnowledgeHandle(
                CURRENT_PROTOCOL_VERSION,
                knowledgeKind,
                conceptId,
                revisionId,
                KnowledgeRouteTarget.DOCUMENT,
                collectionKey,
                documentId,
                chunkIndex);
    }

    private static boolean isSearchableType(KnowledgeConceptType knowledgeKind) {
        return knowledgeKind != KnowledgeConceptType.USER_PREFERENCE;
    }
}
