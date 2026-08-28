package com.harness.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBlockTest {

    @Test
    void preservesNestedStructuredDataDuringRoundTrip() {
        MessageBlock block = new MessageBlock(
                MessageBlock.BlockType.STRUCTURED_DATA,
                null,
                null,
                Map.of("data", Map.of(
                        "customer", Map.of("id", "c-1"),
                        "eligible", true)));

        String json = MessageBlock.toJson(List.of(block));
        List<MessageBlock> restored = MessageBlock.fromJson(json);

        assertThat(json).doesNotContain("\"text\":null", "\"artifactId\":null");
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).type())
                .isEqualTo(MessageBlock.BlockType.STRUCTURED_DATA);
        assertThat(restored.get(0).text()).isNull();
        assertThat(restored.get(0).artifactId()).isNull();
        assertThat(restored.get(0).metadata().get("data"))
                .isEqualTo(Map.of(
                        "customer", Map.of("id", "c-1"),
                        "eligible", true));
    }
}
