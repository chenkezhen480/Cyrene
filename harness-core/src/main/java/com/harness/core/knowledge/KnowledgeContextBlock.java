package com.harness.core.knowledge;

import java.util.Objects;

public record KnowledgeContextBlock(
        KnowledgeConceptType conceptType,
        String conceptId,
        String revisionId,
        String title,
        String content,
        String hitReason
) {
    public KnowledgeContextBlock {
        conceptType = Objects.requireNonNull(conceptType, "conceptType");
        conceptId = KnowledgeModelSupport.requiredText(conceptId, "conceptId", 64);
        revisionId = KnowledgeModelSupport.requiredText(revisionId, "revisionId", 64);
        title = KnowledgeModelSupport.requiredText(title, "title", 512);
        content = Objects.requireNonNull(content, "content");
        hitReason = KnowledgeModelSupport.requiredText(hitReason, "hitReason", 256);
    }

    public String render() {
        return """
                <knowledge-block conceptType="%s" conceptId="%s" revisionId="%s">
                title: %s
                hitReason: %s
                content:
                %s
                </knowledge-block>
                """.formatted(
                conceptType.displayName(), conceptId, revisionId, title, hitReason, content).strip();
    }
}
