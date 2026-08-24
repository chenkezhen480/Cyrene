package com.harness.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.StructuredOutputException;
import com.harness.core.model.FinalOutputContract;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelProviderStructuredOutputTest {

    @Test
    void unsupportedProviderFailsExplicitlyWithoutPromptFallback() throws Exception {
        ChatModelProvider provider = new ChatModelProvider() {
            @Override
            public ChatModel chatModel() {
                return new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        throw new AssertionError("Model must not be called");
                    }
                };
            }

            @Override
            public String providerName() {
                return "unsupported";
            }

            @Override
            public String modelName() {
                return "test-model";
            }
        };
        var schema = new ObjectMapper().readTree("""
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string"}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """);
        var contract = new FinalOutputContract.JsonSchema("answer", schema, true);

        assertThatThrownBy(() -> provider.responseFormat(contract))
                .isInstanceOf(StructuredOutputException.class)
                .extracting(error -> ((StructuredOutputException) error).code())
                .isEqualTo(StructuredOutputException.Code.STRUCTURED_OUTPUT_UNSUPPORTED);
        assertThatThrownBy(provider::structuredChatModel)
                .isInstanceOf(StructuredOutputException.class)
                .extracting(error -> ((StructuredOutputException) error).code())
                .isEqualTo(StructuredOutputException.Code.STRUCTURED_OUTPUT_UNSUPPORTED);
    }
}
