package com.harness.core.knowledge;

import java.time.Instant;

public record KnowledgeConceptCursor(Instant updatedAt, String conceptId) {
    public KnowledgeConceptCursor {
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt is required");
        }
        if (conceptId == null || conceptId.isBlank()) {
            throw new IllegalArgumentException("conceptId is required");
        }
    }
}
