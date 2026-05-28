package com.harness.ai.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 1. General Chat Model Provider
 * Handles: dialogue, tool calling, reasoning, ReAct loop.
 * Backed by LangChain4j ChatModel.
 */
public interface ChatModelProvider {

    ChatModel chatModel();

    default StreamingChatModel streamingModel() {
        return null;
    }

    String providerName();
    String modelName();
}
