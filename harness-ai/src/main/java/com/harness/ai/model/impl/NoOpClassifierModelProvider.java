package com.harness.ai.model.impl;

import com.harness.ai.model.ClassifierModelProvider;
import dev.langchain4j.model.chat.ChatModel;

/**
 * 分类器模型未配置时的空实现，Tier 2 整层跳过。
 */
public class NoOpClassifierModelProvider implements ClassifierModelProvider {

    @Override
    public ChatModel chatModel() { return null; }

    @Override
    public boolean isAvailable() { return false; }

    @Override
    public String providerName() { return "none"; }

    @Override
    public String modelName() { return "none"; }
}
