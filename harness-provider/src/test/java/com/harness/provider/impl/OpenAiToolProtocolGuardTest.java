package com.harness.provider.impl;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiToolProtocolGuardTest {

    @Test
    void rejectsToolFinishReasonWithoutStructuredToolCall() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("plain text"))
                .metadata(ChatResponseMetadata.builder()
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build())
                .build();

        assertThatThrownBy(() -> OpenAiToolProtocolGuard.validate(toolRequest(), response))
                .isInstanceOf(MalformedToolResponseException.class)
                .hasMessageContaining(MalformedToolResponseException.CODE);
    }

    @Test
    void rejectsLeakedDsmlContentEvenWhenProviderReportsStop() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        <｜｜DSML｜｜ calls>
                        <｜｜DSML｜｜ invoke name="lookup">
                        </｜｜DSML｜｜ invoke>
                        </｜｜DSML｜｜ calls>
                        """))
                .metadata(ChatResponseMetadata.builder()
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();

        assertThatThrownBy(() -> OpenAiToolProtocolGuard.validate(toolRequest(), response))
                .isInstanceOf(MalformedToolResponseException.class)
                .hasMessageContaining("protocol markup leaked");
    }

    @Test
    void acceptsOrdinaryTextAndStructuredToolCalls() {
        ChatResponse text = ChatResponse.builder()
                .aiMessage(AiMessage.from("final answer"))
                .metadata(ChatResponseMetadata.builder()
                        .finishReason(FinishReason.STOP)
                        .build())
                .build();
        OpenAiToolProtocolGuard.validate(toolRequest(), text);

        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1")
                .name("lookup")
                .arguments("{}")
                .build();
        ChatResponse tool = ChatResponse.builder()
                .aiMessage(AiMessage.from("", List.of(call)))
                .metadata(ChatResponseMetadata.builder()
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build())
                .build();
        OpenAiToolProtocolGuard.validate(toolRequest(), tool);
    }

    @Test
    void streamingGuardWithholdsLeakedProtocolPrefixAndFailsBeforeUserText() {
        StreamingChatModel raw = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("<");
                handler.onPartialResponse("｜｜DSML｜｜");
                handler.onPartialResponse(" calls>\n");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("<｜｜DSML｜｜ calls>"))
                        .metadata(ChatResponseMetadata.builder()
                                .finishReason(FinishReason.STOP)
                                .build())
                        .build());
            }
        };

        List<String> tokens = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        OpenAiToolProtocolGuard.streaming(raw).chat(toolRequest(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                tokens.add(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
            }
        });

        assertThat(tokens).isEmpty();
        assertThat(error.get()).isInstanceOf(MalformedToolResponseException.class);
    }

    private static ChatRequest toolRequest() {
        ToolSpecification tool = ToolSpecification.builder()
                .name("lookup")
                .description("Lookup")
                .parameters(JsonObjectSchema.builder()
                        .additionalProperties(false)
                        .build())
                .build();
        return ChatRequest.builder()
                .messages(UserMessage.from("lookup"))
                .parameters(ChatRequestParameters.builder()
                        .toolSpecifications(List.of(tool))
                        .build())
                .build();
    }
}
