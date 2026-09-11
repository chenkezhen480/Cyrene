package com.harness.core.knowledge;

import java.time.Instant;

public record ArtifactCursor(Instant createdAt, String artifactId) {
    public ArtifactCursor {
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId is required");
        }
    }
}
