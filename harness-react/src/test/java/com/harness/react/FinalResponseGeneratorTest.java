package com.harness.react;

import com.harness.core.model.CancellationToken;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinalResponseGeneratorTest {

    @Test
    void streamsFinalAnswerDirectlyWithoutProtocolMarkupInspection() {
        List<String> streamed = new ArrayList<>();
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            List<String> tokens = List.of("The ", "final ", "answer.");
            tokens.forEach(handler::onPartialResponse);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(String.join("", tokens)))
                    .build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        FinalResponseGenerator generator = new FinalResponseGenerator(provider(model), 30);
        FinalResponseGenerator.Result result = generator.generateStreaming(
                "system", planningMessages(), null, tokenListener(streamed), null);

        assertThat(String.join("", streamed)).isEqualTo("The final answer.");
        assertThat(result.response().aiMessage().text()).isEqualTo("The final answer.");
    }

    private static List<ChatMessage> planningMessages() {
        return new ArrayList<>(List.of(
                dev.langchain4j.data.message.SystemMessage.from("system"),
                dev.langchain4j.data.message.UserMessage.from("user")));
    }

    private static ReActListener tokenListener(List<String> sink) {
        return new ReActListener() {
            @Override
            public void onStep(com.harness.core.model.ReActStep step) {
            }

            @Override
            public void onToken(String token) {
                sink.add(token);
            }
        };
    }

    private static ChatModelProvider provider(StreamingChatModel streamingModel) {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.streamingModel()).thenReturn(streamingModel);
        when(provider.chatModel()).thenReturn(mock(ChatModel.class));
        return provider;
    }
}
