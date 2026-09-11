package com.harness.core.knowledge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PreferenceActivationTagRegistry {

    public static final String GENERAL_RESPONSE = "GENERAL_RESPONSE";
    public static final String CODE_TASK = "CODE_TASK";
    public static final String IMAGE_TASK = "IMAGE_TASK";
    public static final String REPORT_TASK = "REPORT_TASK";

    private final Set<String> tags;

    public PreferenceActivationTagRegistry(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("at least one activation tag is required");
        }
        LinkedHashSet<String> validated = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = KnowledgeModelSupport.requiredText(tag, "activationTag", 64);
            if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("activationTag must use upper snake case: " + normalized);
            }
            validated.add(normalized);
        }
        this.tags = Set.copyOf(validated);
    }

    public static PreferenceActivationTagRegistry standard() {
        return new PreferenceActivationTagRegistry(Set.of(
                GENERAL_RESPONSE,
                CODE_TASK,
                IMAGE_TASK,
                REPORT_TASK));
    }

    public boolean contains(String tag) {
        return tag != null && tags.contains(tag.trim());
    }

    public Set<String> validate(Collection<String> candidateTags) {
        if (candidateTags == null || candidateTags.isEmpty()) {
            throw new IllegalArgumentException("activationTags must not be empty");
        }
        LinkedHashSet<String> validated = new LinkedHashSet<>();
        for (String candidateTag : candidateTags) {
            String normalized = KnowledgeModelSupport.requiredText(candidateTag, "activationTag", 64);
            if (!tags.contains(normalized)) {
                throw new IllegalArgumentException("unregistered activationTag: " + normalized);
            }
            validated.add(normalized);
        }
        return Set.copyOf(validated);
    }

    public Set<String> tags() {
        return tags;
    }
}
