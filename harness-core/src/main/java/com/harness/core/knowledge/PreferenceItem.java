package com.harness.core.knowledge;

import java.util.Set;

public record PreferenceItem(
        String itemId,
        String statement,
        Set<String> activationTags
) {
    public PreferenceItem {
        itemId = KnowledgeModelSupport.requiredText(itemId, "itemId", 64);
        statement = KnowledgeModelSupport.requiredText(statement, "statement", 2048);
        activationTags = KnowledgeModelSupport.immutableSet(activationTags);
        if (activationTags.isEmpty()) {
            throw new IllegalArgumentException("preference item requires activationTags");
        }
    }
}
