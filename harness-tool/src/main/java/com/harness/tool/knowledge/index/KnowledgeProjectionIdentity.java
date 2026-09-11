package com.harness.tool.knowledge.index;

/** Minimal stable identity used by paginated projection reconciliation. */
public record KnowledgeProjectionIdentity(String revisionId, String conceptId) {
    public KnowledgeProjectionIdentity {
        if (revisionId == null || revisionId.isBlank()
                || conceptId == null || conceptId.isBlank()) {
            throw new IllegalArgumentException("revisionId and conceptId are required");
        }
        revisionId = revisionId.trim();
        conceptId = conceptId.trim();
    }
}
