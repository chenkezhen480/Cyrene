package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/** Stable compound cursor matching Revision Source storage order. */
public record KnowledgeSourceCursor(
        Instant observedAt,
        KnowledgeSourceType sourceType,
        String sourceId
) {
    public KnowledgeSourceCursor {
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 128) {
            throw new IllegalArgumentException(
                    "sourceId is required and must not exceed 128 characters");
        }
        sourceId = sourceId.trim();
    }

    public static KnowledgeSourceCursor from(KnowledgeSource source) {
        Objects.requireNonNull(source, "source");
        return new KnowledgeSourceCursor(
                source.observedAt(), source.sourceType(), source.sourceId());
    }

    public String encode() {
        return KnowledgeCursorSupport.encode(observedAt.toString()) + "."
                + sourceType.name() + "." + KnowledgeCursorSupport.encode(sourceId);
    }

    public static KnowledgeSourceCursor parse(String value) {
        try {
            String[] parts = KnowledgeCursorSupport.split(value, 3, "Knowledge Source");
            return new KnowledgeSourceCursor(
                    Instant.parse(KnowledgeCursorSupport.decode(parts[0])),
                    KnowledgeSourceType.valueOf(parts[1]),
                    KnowledgeCursorSupport.decode(parts[2]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Knowledge Source cursor", e);
        }
    }
}
