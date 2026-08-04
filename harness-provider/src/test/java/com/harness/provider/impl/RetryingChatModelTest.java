package com.harness.provider.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryingChatModelTest {

    @Mock ChatModel delegate;

    private ChatResponse mockResponse(String text) {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.aiMessage()).thenReturn(AiMessage.from(text));
        return resp;
    }

    private ChatRequest sampleRequest() {
        return ChatRequest.builder()
                .messages(List.<ChatMessage>of(UserMessage.from("hello")))
                .build();
    }

    @Test
    void chat_successOnFirstTry_returnsResult() {
        ChatResponse expected = mockResponse("hi");
        when(delegate.chat(any(ChatRequest.class))).thenReturn(expected);

        RetryingChatModel model = new RetryingChatModel(delegate);
        ChatResponse result = model.chat(sampleRequest());

        assertThat(result.aiMessage().text()).isEqualTo("hi");
        verify(delegate, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_retryableError_thenSuccess_retriesOnce() {
        ChatResponse expected = mockResponse("recovered");
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("429 rate limit exceeded"))
                .thenReturn(expected);

        RetryingChatModel model = new RetryingChatModel(delegate);
        ChatResponse result = model.chat(sampleRequest());

        assertThat(result.aiMessage().text()).isEqualTo("recovered");
        verify(delegate, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_nonRetryableError_throwsImmediately() {
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("invalid API key"));

        RetryingChatModel model = new RetryingChatModel(delegate);

        assertThatThrownBy(() -> model.chat(sampleRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("invalid API key");

        verify(delegate, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_retryableError_maxRetriesExhausted_rethrows() {
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("503 service unavailable"));

        RetryingChatModel model = new RetryingChatModel(delegate);

        assertThatThrownBy(() -> model.chat(sampleRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("503");

        verify(delegate, times(3)).chat(any(ChatRequest.class));
    }

    @Test
    void chat_timeoutError_isRetryable() {
        ChatResponse expected = mockResponse("ok");
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("connection timed out"))
                .thenReturn(expected);

        RetryingChatModel model = new RetryingChatModel(delegate);
        ChatResponse result = model.chat(sampleRequest());

        assertThat(result.aiMessage().text()).isEqualTo("ok");
        verify(delegate, times(2)).chat(any(ChatRequest.class));
    }
}
