package com.harness.provider.impl;

import com.harness.provider.SmallTaskModelProvider;
import dev.langchain4j.model.chat.ChatModel;

/** Disables model-backed small tasks while rule-based processing remains available. */
public final class NoOpSmallTaskModelProvider implements SmallTaskModelProvider {
    @Override public ChatModel chatModel() { return null; }
    @Override public boolean isAvailable() { return false; }
    @Override public String providerName() { return "none"; }
    @Override public String modelName() { return "none"; }
}
