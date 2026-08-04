package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * ChatModelProvider 装饰器：持有预创建的 Semaphore 包装模型实例。
 * 委托元数据方法给原始 provider，chatModel/streamingModel 返回预创建的实例。
 */
public class SemaphoreChatModelProvider implements ChatModelProvider {

    private final ChatModelProvider delegate;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;

    public SemaphoreChatModelProvider(ChatModelProvider delegate,
                                       ChatModel chatModel,
                                       StreamingChatModel streamingModel) {
        this.delegate = delegate;
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
    }

    @Override
    public ChatModel chatModel() {
        return chatModel;
    }

    @Override
    public StreamingChatModel streamingModel() {
        return streamingModel;
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    @Override
    public int contextWindow() {
        return delegate.contextWindow();
    }
}
