package com.harness.core.knowledge;

/** Stable cursor for either outgoing or incoming Concept links. */
public record KnowledgeLinkCursor(String counterpartConceptId, KnowledgeLinkType linkType) {
    public KnowledgeLinkCursor {
        if (counterpartConceptId == null
                || counterpartConceptId.isBlank()
                || counterpartConceptId.length() > 64) {
            throw new IllegalArgumentException(
                    "counterpartConceptId is required and must not exceed 64 characters");
        }
        counterpartConceptId = counterpartConceptId.trim();
        if (linkType == null) {
            throw new IllegalArgumentException("linkType is required");
        }
    }

    public static KnowledgeLinkCursor outgoing(KnowledgeLink link) {
        if (link == null) {
            throw new IllegalArgumentException("link is required");
        }
        return new KnowledgeLinkCursor(link.toConceptId(), link.linkType());
    }

    public static KnowledgeLinkCursor incoming(KnowledgeLink link) {
        if (link == null) {
            throw new IllegalArgumentException("link is required");
        }
        return new KnowledgeLinkCursor(link.fromConceptId(), link.linkType());
    }

    public String encode() {
        return KnowledgeCursorSupport.encode(counterpartConceptId) + "." + linkType.name();
    }

    public static KnowledgeLinkCursor parse(String value) {
        try {
            String[] parts = KnowledgeCursorSupport.split(value, 2, "Knowledge Link");
            return new KnowledgeLinkCursor(
                    KnowledgeCursorSupport.decode(parts[0]),
                    KnowledgeLinkType.valueOf(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid Knowledge Link cursor", e);
        }
    }
}
