package com.harness.provider.impl;

import com.harness.provider.VisionModelProvider;
import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigKey;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;

public class AnthropicVisionModelProvider implements VisionModelProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ChatModel chatModel;

    public AnthropicVisionModelProvider(ModelConfig cfg) {
        boolean dedicatedVision = !cfg.getString(ModelConfigKey.VISION_PROVIDER, "").isBlank();
        this.apiKey = cfg.requireString(dedicatedVision
                ? ModelConfigKey.VISION_API_KEY
                : ModelConfigKey.CHAT_API_KEY);
        this.baseUrl = cfg.getString(
                dedicatedVision ? ModelConfigKey.VISION_BASE_URL : ModelConfigKey.CHAT_BASE_URL,
                "https://api.anthropic.com");
        this.model = cfg.getString(
                dedicatedVision ? ModelConfigKey.VISION_MODEL : ModelConfigKey.CHAT_MODEL,
                "claude-sonnet-4-6");
        this.chatModel = AnthropicChatModel.builder()
                .apiKey(apiKey).baseUrl(baseUrl).modelName(this.model).build();
    }

    @Override
    public String analyze(String prompt, Image image) {
        UserMessage msg = UserMessage.from(TextContent.from(prompt), ImageContent.from(image));
        ChatResponse response = chatModel.chat(msg);
        return response.aiMessage().text();
    }

    @Override
    public String analyze(String prompt, List<Image> images) {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(prompt));
        for (Image img : images) {
            contents.add(ImageContent.from(img));
        }
        ChatResponse response = chatModel.chat(UserMessage.from(contents));
        return response.aiMessage().text();
    }

    @Override
    public boolean isAvailable() { return true; }
    @Override public String providerName() { return "anthropic"; }
    @Override public String modelName() { return model; }
}
