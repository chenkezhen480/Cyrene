package com.harness.provider;

import dev.langchain4j.model.chat.ChatModel;

/** Lightweight internal model used by bounded tasks such as GapAnalyzer Tier 2. */
public interface SmallTaskModelProvider {
    ChatModel chatModel();
    String providerName();
    String modelName();
    default boolean isAvailable() { return true; }
}
