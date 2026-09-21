package com.harness.input.gap;

import com.harness.provider.RoutingModelProvider;
import java.util.Objects;

/** Adapts the dedicated routing model to the input pipeline. */
public class GapModelAnalyzer {
    private final RoutingModelProvider provider;

    public GapModelAnalyzer(RoutingModelProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public boolean isAvailable() { return provider.isAvailable(); }

    public GapAnalysis infer(String query) {
        if (!isAvailable() || query == null || query.isBlank()) return null;
        RoutingModelProvider.Decision decision = provider.route(query);
        return new GapAnalysis(decision.needsKnowledgeBase(),
                decision.thinkingLevel() != com.harness.core.model.ThinkingLevel.OFF,
                decision.needsWebSearch(), "jev", decision.thinkingLevel());
    }
}
