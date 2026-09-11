package com.harness.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeLink(
        String fromConceptId,
        String toConceptId,
        KnowledgeLinkType linkType,
        Instant createdAt
) {
    public KnowledgeLink {
        fromConceptId = KnowledgeModelSupport.requiredText(fromConceptId, "fromConceptId", 64);
        toConceptId = KnowledgeModelSupport.requiredText(toConceptId, "toConceptId", 64);
        linkType = Objects.requireNonNull(linkType, "linkType");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (fromConceptId.equals(toConceptId)) {
            throw new IllegalArgumentException("knowledge links must connect different concepts");
        }
    }
}
