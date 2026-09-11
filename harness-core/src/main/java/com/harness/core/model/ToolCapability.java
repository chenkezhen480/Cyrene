package com.harness.core.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Stable capability vocabulary used by trace dependency extraction. */
public enum ToolCapability {
    RETRIEVAL("capability:retrieval"),
    READ("capability:read"),
    GENERATION("capability:generation"),
    MUTATION("capability:mutation"),
    ORCHESTRATION("capability:orchestration"),
    UNKNOWN("capability:unknown");

    private final String tag;

    ToolCapability(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public static ToolCapability fromTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return UNKNOWN;
        }
        ToolCapability found = null;
        for (String tag : tags) {
            ToolCapability candidate = fromTag(tag);
            if (candidate == null) {
                continue;
            }
            if (found != null && found != candidate) {
                throw new IllegalArgumentException("ToolSpec may declare only one capability tag");
            }
            found = candidate;
        }
        return found == null ? UNKNOWN : found;
    }

    private static ToolCapability fromTag(String tag) {
        if (tag == null) {
            return null;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(value -> value.tag.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
