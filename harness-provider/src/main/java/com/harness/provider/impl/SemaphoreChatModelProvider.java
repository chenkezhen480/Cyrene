package com.harness.provider.impl;

import com.harness.provider.ChatModelProvider;
import com.harness.core.model.FinalOutputContract;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * ChatModelProvider 装饰器：持有预创建的 Semaphore 包装模型实例。
 * 委托元数据方法给原始 provider，chatModel/streamingModel 返回预创建的实例。
 */
public class SemaphoreChatModelProvider implements ChatModelProvider {

    private final ChatModelProvider delegate;
    private final ChatModel chatModel;
    private final ChatModel structuredChatModel;
    private final StreamingChatModel streamingModel;

    public SemaphoreChatModelProvider(ChatModelProvider delegate,
                                       ChatModel chatModel,
                                       ChatModel structuredChatModel,
                                       StreamingChatModel streamingModel) {
        this.delegate = delegate;
        this.chatModel = chatModel;
        this.structuredChatModel = structuredChatModel;
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

    @Override
    public ChatRequestParameters planningRequestParameters(
            Boolean enableThinking,
            List<ToolSpecification> toolSpecifications
    ) {
        return delegate.planningRequestParameters(enableThinking, toolSpecifications);
    }

    @Override
    public boolean supportsStructuredOutput() {
        return delegate.supportsStructuredOutput();
    }

    @Override
    public ChatModel structuredChatModel() {
        return structuredChatModel;
    }

    @Override
    public ResponseFormat responseFormat(FinalOutputContract outputContract) {
        return delegate.responseFormat(outputContract);
    }
}
