package com.harness.provider;

import com.harness.core.text.TextTokenEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

import java.util.Objects;

/** Text token estimator backed by the tokenizer used by LangChain4j's OpenAI adapter. */
public final class OpenAiTextTokenEstimator implements TextTokenEstimator {

    private final String modelName;
    private final OpenAiTokenCountEstimator delegate;

    public OpenAiTextTokenEstimator(String modelName) {
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.delegate = new OpenAiTokenCountEstimator(modelName);
    }

    @Override
    public int estimate(String text) {
        return text == null || text.isEmpty() ? 0 : delegate.estimateTokenCountInText(text);
    }

    @Override
    public String strategyName() {
        return "openai-tokenizer:" + modelName;
    }
}
