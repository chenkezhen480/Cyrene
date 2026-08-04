package com.harness.provider;

import dev.langchain4j.model.chat.ChatModel;

/**
 * 7. Classifier Model Provider (意图分类/路由)
 * 用于 GapAnalyzer Tier 2 轻量 LLM 分类，thinking 固定关闭，非流式。
 */
public interface ClassifierModelProvider {

    /** 同步 ChatModel，thinking 已在构建时关闭 */
    ChatModel chatModel();

    String providerName();

    String modelName();

    /** 是否已配置（NoOp 实现返回 false） */
    default boolean isAvailable() {
        return true;
    }
}
