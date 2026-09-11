package com.harness.core.knowledge;

import java.util.Set;

public record PreferenceKeyDefinition(
        String key,
        Set<String> defaultActivationTags
) {
    public PreferenceKeyDefinition {
        key = KnowledgeModelSupport.requiredText(key, "key", 256);
        if (!key.matches("[a-z][A-Za-z0-9]*(\\.[a-z][A-Za-z0-9]*)*|other")) {
            throw new IllegalArgumentException("preference key must use dotted camelCase: " + key);
        }
        defaultActivationTags = KnowledgeModelSupport.immutableSet(defaultActivationTags);
        if (!PreferenceKeyRegistry.OTHER_KEY.equals(key) && defaultActivationTags.isEmpty()) {
            throw new IllegalArgumentException("registered preference key requires activation tags");
        }
        if (PreferenceKeyRegistry.OTHER_KEY.equals(key) && !defaultActivationTags.isEmpty()) {
            throw new IllegalArgumentException("other preference key does not have default activation tags");
        }
    }
}
