package com.harness.core.knowledge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public record PreferenceActivationContext(Set<String> activeTags) {

    public PreferenceActivationContext {
        activeTags = KnowledgeModelSupport.immutableSet(activeTags);
    }

    public static PreferenceActivationContext forFinalResponse(Collection<String> taskTags) {
        LinkedHashSet<String> active = new LinkedHashSet<>();
        active.add(PreferenceActivationTagRegistry.GENERAL_RESPONSE);
        if (taskTags != null) {
            active.addAll(taskTags);
        }
        return new PreferenceActivationContext(active);
    }

    public boolean matches(Set<String> preferenceTags) {
        if (preferenceTags == null || preferenceTags.isEmpty()) {
            return false;
        }
        return preferenceTags.stream().anyMatch(activeTags::contains);
    }
}
