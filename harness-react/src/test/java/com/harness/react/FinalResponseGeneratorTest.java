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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinalResponseGeneratorTest {

    /** The exact markup from the reported leak, split the way the provider streamed it. */
    private static final List<String> DEEPSEEK_LEAK = List.of(
            "<", "｜", "DSML", "｜", " tool_calls", ">\n",
            "｜ <", "｜ invoke", " name=\"knowledge_search\"", ">\n",
            "query: 上一次会话 用户聊了什么", "\n</", "｜ tool_calls", "｜>");

    @Test
    void flagsProviderToolCallEnvelopesWithoutFlaggingProse() {
        assertThat(FinalResponseGenerator.isToolCallMarkup(String.join("", DEEPSEEK_LEAK))).isTrue();
        assertThat(FinalResponseGenerator.isToolCallMarkup("<|DSML| tool_calls>")).isTrue();
        assertThat(FinalResponseGenerator.isToolCallMarkup("<tool_call>\n{\"name\":\"x\"}")).isTrue();
        assertThat(FinalResponseGenerator.isToolCallMarkup("  <function_call>")).isTrue();

        assertThat(FinalResponseGenerator.isToolCallMarkup("上一次我们聊了 Redis 缓存")).isFalse();
        assertThat(FinalResponseGenerator.isToolCallMarkup("<div>HTML 开头是合法正文")).isFalse();
        assertThat(FinalResponseGenerator.isToolCallMarkup("<tool_")).isFalse();
        assertThat(FinalResponseGenerator.isToolCallMarkup(null)).isFalse();
    }

    @Test
    void holdsOnlyWhileTheOpeningCharactersCouldStillBecomeAMarker() {
        assertThat(FinalResponseGenerator.couldBecomeToolCallMarkup("")).isTrue();
        assertThat(FinalResponseGenerator.couldBecomeToolCallMarkup("  ")).isTrue();
        assertThat(FinalResponseGenerator.couldBecomeToolCallMarkup("<")).isTrue();
        assertThat(FinalResponseGenerator.couldBecomeToolCallMarkup("<｜DSML")).isTrue();

        // Anything that has already diverged must be released immediately: the guard may not
        // add latency to an ordinary answer.
        assertThat(FinalResponseGenerator.couldBecomeToolCallMarkup("上")).isFalse();
        assertThat(FinalResponseGenerator.couldBecomeToolCallMarkup("<div>")).isFalse();
    }

    @Test
    void aLeakedToolCallIsNeverStreamedAndIsRegeneratedOnce() {
        List<String> streamed = new ArrayList<>();
        StreamingChatModel model = mock(StreamingChatModel.class);
        List<String> requests = new ArrayList<>();
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            String system = request.messages().stream()
                    .filter(SystemMessage -> SystemMessage instanceof dev.langchain4j.data.message.SystemMessage)
                    .map(message -> ((dev.langchain4j.data.message.SystemMessage) message).text())
                    .findFirst().orElse("");
            requests.add(system);
            List<String> tokens = requests.size() == 1
                    ? DEEPSEEK_LEAK
                    : List.of("上一次我们聊了 ", "Redis 缓存与本地缓存的取舍。");
            tokens.forEach(handler::onPartialResponse);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(String.join("", tokens)))
                    .build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        FinalResponseGenerator generator = new FinalResponseGenerator(provider(model), 30);

        FinalResponseGenerator.Result result = generator.generateStreaming(
                "system", planningMessages(), null,
                tokenListener(streamed), null);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1))
                .as("the retry must name the problem")
                .contains("tool-call syntax");
        assertThat(String.join("", streamed))
                .as("no tool syntax may reach the client, and the retry must")
                .isEqualTo("上一次我们聊了 Redis 缓存与本地缓存的取舍。");
        assertThat(result.response().aiMessage().text()).contains("Redis 缓存");
    }

    @Test
    void twoLeaksInARowSurfaceAsAnErrorRatherThanAsMarkup() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            DEEPSEEK_LEAK.forEach(handler::onPartialResponse);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(String.join("", DEEPSEEK_LEAK)))
                    .build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        FinalResponseGenerator generator = new FinalResponseGenerator(provider(model), 30);

        assertThatThrownBy(() -> generator.generateStreaming(
                "system", planningMessages(), null, tokenListener(new ArrayList<>()), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("tool-call syntax");
    }

    /** finalMessages replaces index 0, so the caller always supplies the loop's own history. */
    private static List<ChatMessage> planningMessages() {
        return new ArrayList<>(List.of(
                dev.langchain4j.data.message.SystemMessage.from("system"),
                dev.langchain4j.data.message.UserMessage.from("用户问题")));
    }

    /** onStep is the interface's only abstract method, so a token listener needs a class. */
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
