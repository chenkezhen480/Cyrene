package com.harness.tool.knowledge.okf;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeNamespaceType;

import java.util.Objects;

/** An authorization boundary that is materialized as one OKF bundle. */
public record OkfBundleScope(
        Kind kind,
        String tenantId,
        String userId,
        String namespaceKey,
        String graphId,
        String schemaId
) {

    public OkfBundleScope {
        kind = Objects.requireNonNull(kind, "kind");
        tenantId = normalize(tenantId);
        userId = normalize(userId);
        namespaceKey = normalize(namespaceKey);
        graphId = normalize(graphId);
        schemaId = normalize(schemaId);
        switch (kind) {
            case USER -> {
                if (userId == null || namespaceKey != null
                        || graphId != null || schemaId != null) {
                    throw new IllegalArgumentException(
                            "user bundle requires userId and forbids namespaceKey");
                }
            }
            case TENANT_OPERATION -> {
                if (tenantId == null || userId != null || namespaceKey != null
                        || graphId != null || schemaId != null) {
                    throw new IllegalArgumentException(
                            "tenant operation bundle requires tenantId only");
                }
            }
            case GLOBAL_OPERATION -> {
                if (tenantId != null || userId != null || namespaceKey != null
                        || graphId != null || schemaId != null) {
                    throw new IllegalArgumentException(
                            "global operation bundle forbids tenantId, userId and namespaceKey");
                }
            }
            case COLLECTION -> {
                if (userId != null || namespaceKey == null
                        || graphId != null || schemaId != null) {
                    throw new IllegalArgumentException(
                            "collection bundle requires namespaceKey and forbids graph scope");
                }
            }
            case GRAPH -> {
                if (userId != null || namespaceKey == null
                        || graphId == null || schemaId == null
                        || !namespaceKey.equals(graphNamespaceKey(graphId, schemaId))) {
                    throw new IllegalArgumentException(
                            "graph bundle requires graphId and schemaId");
                }
            }
        }
    }

    public static OkfBundleScope user(String tenantId, String userId) {
        return new OkfBundleScope(Kind.USER, tenantId, userId, null, null, null);
    }

    public static OkfBundleScope tenantOperation(String tenantId) {
        return new OkfBundleScope(Kind.TENANT_OPERATION, tenantId, null, null, null, null);
    }

    public static OkfBundleScope globalOperation() {
        return new OkfBundleScope(Kind.GLOBAL_OPERATION, null, null, null, null, null);
    }

    public static OkfBundleScope collection(String tenantId, String collectionKey) {
        return new OkfBundleScope(Kind.COLLECTION, tenantId, null, collectionKey, null, null);
    }

    public static OkfBundleScope graph(
            String tenantId,
            String graphId,
            String schemaId
    ) {
        return new OkfBundleScope(
                Kind.GRAPH, tenantId, null,
                graphNamespaceKey(graphId, schemaId), graphId, schemaId);
    }

    public boolean permits(KnowledgeConcept concept) {
        Objects.requireNonNull(concept, "concept");
        return switch (kind) {
            case USER -> concept.namespaceType() == KnowledgeNamespaceType.USER_MEMORY
                    && concept.conceptType().isUserOwned()
                    && Objects.equals(tenantId, normalize(concept.tenantId()))
                    && userId.equals(concept.userId());
            case TENANT_OPERATION -> concept.namespaceType() == KnowledgeNamespaceType.OPERATION_MEMORY
                    && concept.conceptType() == KnowledgeConceptType.OPERATION_PLAYBOOK
                    && concept.userId() == null
                    && tenantId.equals(normalize(concept.tenantId()));
            case GLOBAL_OPERATION -> concept.namespaceType() == KnowledgeNamespaceType.OPERATION_MEMORY
                    && concept.conceptType() == KnowledgeConceptType.OPERATION_PLAYBOOK
                    && concept.userId() == null
                    && concept.tenantId() == null;
            case COLLECTION -> concept.namespaceType() == KnowledgeNamespaceType.COLLECTION
                    && concept.conceptType() == KnowledgeConceptType.SOURCE_DOCUMENT
                    && Objects.equals(tenantId, normalize(concept.tenantId()))
                    && namespaceKey.equals(concept.namespaceKey());
            case GRAPH -> concept.namespaceType() == KnowledgeNamespaceType.GRAPH
                    && concept.tenantId() == null
                    && switch (concept.conceptType()) {
                case GRAPH_SCHEMA -> schemaId.equals(concept.namespaceKey())
                        && schemaId.equals(concept.logicalKey());
                case GRAPH_SPACE -> namespaceKey.equals(concept.namespaceKey())
                        && graphId.equals(concept.logicalKey());
                default -> false;
            };
        };
    }

    public String conceptNamespaceKey(KnowledgeConceptType conceptType) {
        Objects.requireNonNull(conceptType, "conceptType");
        if (kind != Kind.GRAPH) return namespaceKey;
        return switch (conceptType) {
            case GRAPH_SCHEMA -> schemaId;
            case GRAPH_SPACE -> namespaceKey;
            default -> throw new IllegalArgumentException(
                    "Concept type is not valid for a graph bundle: " + conceptType);
        };
    }

    private static String graphNamespaceKey(String graphId, String schemaId) {
        String normalizedGraphId = normalize(graphId);
        String normalizedSchemaId = normalize(schemaId);
        if (normalizedGraphId == null || normalizedSchemaId == null) {
            throw new IllegalArgumentException("graphId and schemaId are required");
        }
        return normalizedGraphId + ':' + normalizedSchemaId;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum Kind {
        USER,
        TENANT_OPERATION,
        GLOBAL_OPERATION,
        COLLECTION,
        GRAPH
    }
}
