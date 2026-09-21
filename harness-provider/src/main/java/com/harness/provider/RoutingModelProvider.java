package com.harness.provider;

import com.harness.core.model.ThinkingLevel;

/** Typed pre-loop decisions, independent of text-generation providers. */
public interface RoutingModelProvider {
    Decision route(String query);
    default boolean isAvailable() { return true; }

    record Decision(ThinkingLevel thinkingLevel, boolean needsKnowledgeBase, boolean needsWebSearch) {
        public Decision {
            java.util.Objects.requireNonNull(thinkingLevel, "thinkingLevel");
        }
    }

    RoutingModelProvider DISABLED = new RoutingModelProvider() {
        @Override public Decision route(String query) {
            throw new IllegalStateException("Routing model is disabled");
        }
        @Override public boolean isAvailable() { return false; }
    };
}
