package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * ChatModelProvider 装饰器：持有预创建的 Semaphore 包装模型实例。
 * 委托元数据方法给原始 provider，chatModel/streamingModel 返回预创建的实例。
 */
public class SemaphoreChatModelProvider implements ChatModelProvider {

    private final ChatModelProvider delegate;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;
    private final int contextWindow;
    private final java.util.Set<com.harness.provider.ModalCapability> modalCapabilities;

    public SemaphoreChatModelProvider(ChatModelProvider delegate,
                                       ChatModel chatModel,
                                       StreamingChatModel streamingModel) {
        this(delegate, chatModel, streamingModel, delegate.contextWindow());
    }

    public SemaphoreChatModelProvider(ChatModelProvider delegate,
                                       ChatModel chatModel,
                                       StreamingChatModel streamingModel,
                                       int contextWindow) {
        this(delegate, chatModel, streamingModel, contextWindow,
                delegate.modalCapabilities());
    }

    public SemaphoreChatModelProvider(
            ChatModelProvider delegate,
            ChatModel chatModel,
            StreamingChatModel streamingModel,
            int contextWindow,
            java.util.Set<com.harness.provider.ModalCapability> modalCapabilities
    ) {
        this.delegate = delegate;
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
        this.contextWindow = contextWindow;
        this.modalCapabilities = java.util.Set.copyOf(modalCapabilities);
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
        return contextWindow;
    }

    @Override
    public int timeoutSeconds() { return delegate.timeoutSeconds(); }

    @Override
    public java.util.Set<com.harness.provider.ModalCapability> modalCapabilities() {
        return modalCapabilities;
    }

    @Override
    public ChatRequestParameters planningRequestParameters(
            Boolean enableThinking,
            List<ToolSpecification> toolSpecifications
    ) {
        return delegate.planningRequestParameters(enableThinking, toolSpecifications);
    }

}
