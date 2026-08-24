package com.harness.provider;

import com.harness.core.model.ModelUsage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiResponsesChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelUsageMapperTest {

    @Test
    void map_genericUsage_keepsProviderSpecificFieldsUnknown() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(120, 30))
                        .build())
                .build();

        ModelUsage usage = ChatModelUsageMapper.map(response, 42);

        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.cachedInputTokens()).isNull();
        assertThat(usage.reasoningTokens()).isNull();
        assertThat(usage.cacheWriteTokens()).isNull();
        assertThat(usage.llmLatencyMs()).isEqualTo(42);
    }

    @Test
    void map_openAiUsage_readsCachedAndReasoningTokens() {
        OpenAiTokenUsage tokenUsage = OpenAiTokenUsage.builder()
                .inputTokenCount(200)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(150)
                        .build())
                .outputTokenCount(40)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder()
                        .reasoningTokens(12)
                        .build())
                .totalTokenCount(240)
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .metadata(OpenAiChatResponseMetadata.builder()
                        .tokenUsage(tokenUsage)
                        .build())
                .build();

        ModelUsage usage = ChatModelUsageMapper.map(response, 80);

        assertThat(usage.inputTokens()).isEqualTo(200);
        assertThat(usage.cachedInputTokens()).isEqualTo(150);
        assertThat(usage.outputTokens()).isEqualTo(40);
        assertThat(usage.reasoningTokens()).isEqualTo(12);
        assertThat(usage.cacheHitRatio()).isEqualTo(0.75);
        assertThat(usage.uncachedInputTokens()).isEqualTo(50);
    }

    @Test
    void map_responsesUsage_readsCachedAndReasoningTokens() {
        OpenAiTokenUsage tokenUsage = OpenAiTokenUsage.builder()
                .inputTokenCount(80)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                        .cachedTokens(60)
                        .build())
                .outputTokenCount(20)
                .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder()
                        .reasoningTokens(5)
                        .build())
                .totalTokenCount(100)
                .build();
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .metadata(OpenAiResponsesChatResponseMetadata.builder()
                        .tokenUsage(tokenUsage)
                        .build())
                .build();

        ModelUsage usage = ChatModelUsageMapper.map(response, 31);

        assertThat(usage.inputTokens()).isEqualTo(80);
        assertThat(usage.cachedInputTokens()).isEqualTo(60);
        assertThat(usage.outputTokens()).isEqualTo(20);
        assertThat(usage.reasoningTokens()).isEqualTo(5);
        assertThat(usage.llmLatencyMs()).isEqualTo(31);
    }

    @Test
    void map_missingMetadata_preservesUnknownCounters() {
        ModelUsage usage = ChatModelUsageMapper.map(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(),
                9);

        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
        assertThat(usage.cachedInputTokens()).isNull();
        assertThat(usage.uncachedInputTokens()).isNull();
        assertThat(usage.llmLatencyMs()).isEqualTo(9);
    }
}
