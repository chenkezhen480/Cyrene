package com.harness.provider;

import com.harness.core.model.ModelUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

/** Maps provider response metadata into the framework's neutral usage model. */
public final class ChatModelUsageMapper {

    private ChatModelUsageMapper() {
    }

    public static ModelUsage map(ChatResponse response, long llmLatencyMs) {
        TokenUsage usage = response != null && response.metadata() != null
                ? response.metadata().tokenUsage()
                : null;
        if (usage == null) {
            return new ModelUsage(
                    null, null, null, null, null,
                    llmLatencyMs, null, null);
        }

        Long cachedInputTokens = null;
        Long reasoningTokens = null;
        if (usage instanceof OpenAiTokenUsage openAiUsage) {
            if (openAiUsage.inputTokensDetails() != null
                    && openAiUsage.inputTokensDetails().cachedTokens() != null) {
                cachedInputTokens = openAiUsage.inputTokensDetails().cachedTokens().longValue();
            }
            if (openAiUsage.outputTokensDetails() != null
                    && openAiUsage.outputTokensDetails().reasoningTokens() != null) {
                reasoningTokens = openAiUsage.outputTokensDetails().reasoningTokens().longValue();
            }
        }

        return new ModelUsage(
                (long) usage.inputTokenCount(),
                cachedInputTokens,
                null,
                (long) usage.outputTokenCount(),
                reasoningTokens,
                llmLatencyMs,
                null,
                null);
    }
}
