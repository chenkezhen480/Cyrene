package com.harness.core.knowledge;

import java.util.List;
import java.util.Set;

public record PreferenceRevisionData(
        String preferenceKey,
        String valueText,
        Set<String> activationTags,
        List<PreferenceItem> items
) {
    public PreferenceRevisionData {
        preferenceKey = KnowledgeModelSupport.requiredText(preferenceKey, "preferenceKey", 256);
        valueText = KnowledgeModelSupport.optionalText(valueText, "valueText", 4096);
        activationTags = KnowledgeModelSupport.immutableSet(activationTags);
        items = KnowledgeModelSupport.immutableList(items);

        if (PreferenceKeyRegistry.OTHER_KEY.equals(preferenceKey)) {
            if (valueText != null || !activationTags.isEmpty()) {
                throw new IllegalArgumentException("other preference stores values in items only");
            }
        } else if (valueText == null || activationTags.isEmpty() || !items.isEmpty()) {
            throw new IllegalArgumentException("registered preference requires valueText and activationTags only");
        }
    }
}
