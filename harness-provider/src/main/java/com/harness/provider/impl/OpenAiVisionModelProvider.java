package com.harness.provider.impl;

import com.harness.provider.VisionModelProvider;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;

public class OpenAiVisionModelProvider implements VisionModelProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final ChatModel chatModel;

    public OpenAiVisionModelProvider() {
        EnvConfig cfg = EnvConfig.get();
        boolean dedicatedVision = !cfg.getString(EnvKey.MODEL_VISION_PROVIDER, "").isBlank();
        this.apiKey = cfg.requireString(dedicatedVision
                ? EnvKey.MODEL_VISION_API_KEY
                : EnvKey.MODEL_CHAT_API_KEY);
        this.baseUrl = cfg.getString(
                dedicatedVision ? EnvKey.MODEL_VISION_BASE_URL : EnvKey.MODEL_CHAT_BASE_URL,
                "https://api.openai.com/v1");
        this.model = cfg.getString(
                dedicatedVision ? EnvKey.MODEL_VISION_MODEL : EnvKey.MODEL_CHAT_MODEL,
                "gpt-4o");
        this.chatModel = OpenAiChatModel.builder()
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
    @Override public String providerName() { return "openai"; }
    @Override public String modelName() { return model; }
}
