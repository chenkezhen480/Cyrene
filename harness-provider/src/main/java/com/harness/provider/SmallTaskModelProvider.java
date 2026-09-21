package com.harness.provider;

import dev.langchain4j.model.chat.ChatModel;

/** Lightweight internal model; pre-loop routing uses RoutingModelProvider. */
public interface SmallTaskModelProvider {
    ChatModel chatModel();
    String providerName();
    String modelName();
    default boolean isAvailable() { return true; }
}
