package com.harness.core.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnicodeAwareTextTokenEstimatorTest {

    private final TextTokenEstimator estimator = UnicodeAwareTextTokenEstimator.INSTANCE;

    @Test
    void estimatesLatinRunsCjkAndEmojiWithoutUtf16LengthBias() {
        assertThat(estimator.estimate("hello")).isEqualTo(2);
        assertThat(estimator.estimate("中文")).isEqualTo(2);
        assertThat(estimator.estimate("🙂")).isEqualTo(2);
        assertThat(estimator.estimate("hello 中文 🙂")).isEqualTo(6);
    }

    @Test
    void estimatesCodeUrlsAndIdentifiersDeterministically() {
        String content = """
                https://example.com/a?q=1
                123e4567-e89b-12d3-a456-426614174000
                {"name":"Cyrene"}
                SELECT * FROM users WHERE tenant_id = '000000';
                """;

        int first = estimator.estimate(content);

        assertThat(first).isPositive();
        assertThat(estimator.estimate(content)).isEqualTo(first);
        assertThat(estimator.strategyName()).isEqualTo("unicode-aware-estimate-v1");
    }

    @Test
    void returnsZeroForMissingContent() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("")).isZero();
    }
}
