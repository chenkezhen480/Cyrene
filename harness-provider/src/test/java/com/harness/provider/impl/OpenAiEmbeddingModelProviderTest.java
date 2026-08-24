package com.harness.provider.impl;

import com.harness.core.text.TextTokenEstimator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiEmbeddingModelProviderTest {

    @Test
    void usesOpenAiTokenizerForKnownCompatibleModels() {
        TextTokenEstimator estimator =
                OpenAiEmbeddingModelProvider.createTokenEstimator("text-embedding-3-small");

        assertThat(estimator.strategyName())
                .isEqualTo("openai-tokenizer:text-embedding-3-small");
    }

    @Test
    void usesExplicitUnicodeEstimatorForCompatibleApisWithUnknownModelNames() {
        TextTokenEstimator estimator =
                OpenAiEmbeddingModelProvider.createTokenEstimator("text-embedding-v3");

        assertThat(estimator.strategyName()).isEqualTo("unicode-aware-estimate-v1");
        assertThat(estimator.estimate("中 English 🙂")).isPositive();
    }
}
