package com.harness.ai.model.impl;

import com.harness.ai.model.VisionModelProvider;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
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

    public AnthropicVisionModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        this.apiKey = cfg.requireString(EnvKey.MODEL_VISION_API_KEY);
        this.baseUrl = cfg.getString(EnvKey.MODEL_VISION_BASE_URL, "https://api.anthropic.com");
        this.model = cfg.getString(EnvKey.MODEL_VISION_MODEL, "claude-sonnet-4-6");
    }

    @Override
    public String analyze(String prompt, Image image) {
        ChatModel model = AnthropicChatModel.builder()
                .apiKey(apiKey).baseUrl(baseUrl).modelName(this.model).build();

        UserMessage msg = UserMessage.from(TextContent.from(prompt), ImageContent.from(image));
        ChatResponse response = model.chat(msg);
        return response.aiMessage().text();
    }

    @Override
    public String analyze(String prompt, List<Image> images) {
        ChatModel model = AnthropicChatModel.builder()
                .apiKey(apiKey).baseUrl(baseUrl).modelName(this.model).build();

        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(prompt));
        for (Image img : images) {
            contents.add(ImageContent.from(img));
        }
        ChatResponse response = model.chat(UserMessage.from(contents));
        return response.aiMessage().text();
    }

    @Override
    public boolean isAvailable() { return true; }
    @Override public String providerName() { return "anthropic"; }
    @Override public String modelName() { return model; }
}
