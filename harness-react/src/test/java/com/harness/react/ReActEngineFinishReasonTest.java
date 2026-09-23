package com.harness.react;

import com.harness.core.runtime.RunTrace;
import com.harness.provider.ChatModelProvider;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReActEngineFinishReasonTest {

    @Test
    void rejectsTruncatedFilteredMissingAndContradictoryCompletions() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("call-1").name("tool").arguments("{}").build();
        for (ChatResponse response : List.of(
                response(FinishReason.LENGTH, AiMessage.from("partial")),
                response(FinishReason.CONTENT_FILTER, AiMessage.from("filtered")),
                response(null, AiMessage.from("missing reason")),
                response(FinishReason.TOOL_EXECUTION, AiMessage.from("no calls")),
                response(FinishReason.STOP, AiMessage.from("conflict", List.of(call))))) {
            ChatModelProvider provider = mock(ChatModelProvider.class);
            when(provider.chatModel()).thenReturn(mock(ChatModel.class));
            when(provider.requiresChatCompletionFinishReason()).thenReturn(true);
            when(provider.streamingModel()).thenReturn(new StreamingChatModel() {
                @Override
                public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("partial");
                    handler.onCompleteResponse(response);
                }
            });
            ToolCatalog catalog = mock(ToolCatalog.class);
            when(catalog.getAll()).thenReturn(List.of());
            ReActEngine engine = new ReActEngine(
                    provider, catalog, mock(ToolExecutor.class), null, null, 1);
            ReActRequest request = new ReActRequest(
                    "system", "question", List.of(), RunTrace.noop(),
                    step -> {}, null, null, null);

            assertThatThrownBy(() -> engine.streamExecute(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finish_reason");
        }
    }

    private static ChatResponse response(FinishReason reason, AiMessage message) {
        return ChatResponse.builder().aiMessage(message).finishReason(reason).build();
    }
}
