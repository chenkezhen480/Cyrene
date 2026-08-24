package com.harness.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.StructuredOutputException;
import com.harness.core.model.FinalOutputContract;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinalResponseGeneratorStructuredErrorTest {

    @Test
    void mapsLengthFinishReasonToTruncatedError() throws Exception {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(
                                "{\"answer\":\"cut"))
                        .metadata(ChatResponseMetadata.builder()
                                .finishReason(FinishReason.LENGTH)
                                .build())
                        .build();
            }
        };

        assertError(model, StructuredOutputException.Code.STRUCTURED_OUTPUT_TRUNCATED);
    }

    @Test
    void mapsProviderRefusalToRefusedError() throws Exception {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new ContentFilteredException("refused");
            }
        };

        assertError(model, StructuredOutputException.Code.STRUCTURED_OUTPUT_REFUSED);
    }

    private static void assertError(
            ChatModel structuredModel,
            StructuredOutputException.Code expectedCode
    ) throws Exception {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chatModel()).thenReturn(mock(ChatModel.class));
        when(provider.structuredChatModel()).thenReturn(structuredModel);
        when(provider.responseFormat(any())).thenReturn(ResponseFormat.JSON);
        var schema = new ObjectMapper().readTree("""
                {
                  "type":"object",
                  "properties":{"answer":{"type":"string"}},
                  "required":["answer"],
                  "additionalProperties":false
                }
                """);
        FinalResponseGenerator generator = new FinalResponseGenerator(provider, 30);

        assertThatThrownBy(() -> generator.generateBlocking(
                "system",
                List.of(SystemMessage.from("system"), UserMessage.from("question")),
                null,
                new FinalOutputContract.JsonSchema("answer", schema, true),
                null))
                .isInstanceOf(StructuredOutputException.class)
                .extracting(error -> ((StructuredOutputException) error).code())
                .isEqualTo(expectedCode);
    }
}
