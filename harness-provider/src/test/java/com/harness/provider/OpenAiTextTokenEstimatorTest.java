package com.harness.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiTextTokenEstimatorTest {

    @Test
    void delegatesToOpenAiTokenizerAndExposesItsStrategy() {
        OpenAiTextTokenEstimator estimator =
                new OpenAiTextTokenEstimator("text-embedding-3-small");

        assertThat(estimator.estimate("hello 世界 🙂")).isPositive();
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.strategyName())
                .isEqualTo("openai-tokenizer:text-embedding-3-small");
    }
}
