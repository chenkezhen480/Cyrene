package com.harness.input.memory;

import com.harness.provider.ChatModelProvider;
import com.harness.core.model.MemoryMessage;
import com.harness.core.model.MessageBlock;
import com.harness.core.env.EnvConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryCompressorTest {

    @Mock MessageStore messageStore;
    @Mock SessionStore sessionStore;
    @Mock ChatModelProvider chatModelProvider;
    @Mock ChatModel chatModel;

    MemoryCompressor compressor;

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(
                "HARNESS_CTX_COMPRESS_MAJOR", "85",
                "HARNESS_CTX_COMPRESS_MAJOR_TARGET", "30"
        ));
        compressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);
    }

    private List<MemoryMessage> createMessages(int count) {
        List<MemoryMessage> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String role = (i % 2 == 0) ? "user" : "assistant";
            msgs.add(new MemoryMessage(i, "sess1", role, List.of(new MessageBlock(MessageBlock.BlockType.TEXT, "Message content " + i, null)), false, Instant.now()));
        }
        return msgs;
    }

    private ChatResponse createMockChatResponse(String text) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from(text));
        return response;
    }

    @Test
    void compressIfNeeded_belowThreshold_returnsNone() {
        List<MemoryMessage> messages = createMessages(5);
        var result = compressor.compressIfNeeded("sess1", messages, 2000, 5000, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.NONE);
        assertThat(result.messagesBefore()).isEqualTo(5);
        assertThat(result.messagesAfter()).isEqualTo(5);

        verifyNoInteractions(messageStore, sessionStore);
    }

    @Test
    void compressIfNeeded_aboveThreshold_withChatModel_triggersMajorCompression() {
        List<MemoryMessage> messages = createMessages(10);
        List<MemoryMessage> compressedMessages = createMessages(2);
        ChatResponse chatResponse = createMockChatResponse("This is a compressed summary of the conversation.");

        when(chatModelProvider.chatModel()).thenReturn(chatModel);
        when(chatModel.chat(any(UserMessage.class))).thenReturn(chatResponse);
        when(messageStore.loadForContext("sess1")).thenReturn(compressedMessages);

        var result = compressor.compressIfNeeded("sess1", messages, 5000, 9000, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);
        assertThat(result.messagesBefore()).isEqualTo(10);
        assertThat(result.messagesAfter()).isEqualTo(2);

        ArgumentCaptor<List> summaryCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageStore).save(eq("sess1"), eq("system"), summaryCaptor.capture(), eq(true));
        assertThat(MemoryMessage.text(summaryCaptor.getValue())).contains("compressed summary");

        verify(sessionStore).updateLastActive("sess1");
    }

    @Test
    void compressIfNeeded_aboveThreshold_nullChatModel_usesFallbackTruncation() {
        List<MemoryMessage> messages = createMessages(10);
        List<MemoryMessage> compressedMessages = createMessages(3);

        when(chatModelProvider.chatModel()).thenReturn(null);
        when(messageStore.loadForContext("sess1")).thenReturn(compressedMessages);

        var result = compressor.compressIfNeeded("sess1", messages, 5000, 9000, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);

        ArgumentCaptor<List> summaryCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageStore).save(eq("sess1"), eq("system"), summaryCaptor.capture(), eq(true));
        assertThat(MemoryMessage.text(summaryCaptor.getValue())).contains("[Conversation summary]");

        verify(sessionStore).updateLastActive("sess1");
    }

    @Test
    void compressIfNeeded_exactThreshold_triggersCompression() {
        List<MemoryMessage> messages = createMessages(10);
        List<MemoryMessage> compressedMessages = createMessages(2);
        ChatResponse chatResponse = createMockChatResponse("Summary.");

        when(chatModelProvider.chatModel()).thenReturn(chatModel);
        when(chatModel.chat(any(UserMessage.class))).thenReturn(chatResponse);
        when(messageStore.loadForContext("sess1")).thenReturn(compressedMessages);

        var result = compressor.compressIfNeeded("sess1", messages, 5000, 8500, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);
    }

    @Test
    void compressIfNeeded_justBelowThreshold_returnsNone() {
        List<MemoryMessage> messages = createMessages(5);

        var result = compressor.compressIfNeeded("sess1", messages, 3000, 8400, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.NONE);
        verifyNoInteractions(chatModelProvider);
    }

    // ---- Small context window tests (simulating easy trigger) ----

    @Test
    void smallContextWindow_triggersCompression() {
        // Simulate a 1000-token context window; 85% = 850 tokens
        // 10 messages * ~100 tokens each = 1000 tokens → exceeds 85%
        EnvConfig.init(Map.of(
                "HARNESS_CTX_COMPRESS_MAJOR", "85",
                "HARNESS_CTX_COMPRESS_MAJOR_TARGET", "30"
        ));
        MemoryCompressor smallCompressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);

        List<MemoryMessage> messages = createMessages(10);
        List<MemoryMessage> compressed = createMessages(2);
        ChatResponse chatResponse = createMockChatResponse("Compressed summary of 10 messages.");

        when(chatModelProvider.chatModel()).thenReturn(chatModel);
        when(chatModel.chat(any(UserMessage.class))).thenReturn(chatResponse);
        when(messageStore.loadForContext("sess1")).thenReturn(compressed);

        // totalBudget=1000, totalUsed=900 (90%) → triggers
        var result = smallCompressor.compressIfNeeded("sess1", messages, 900, 900, 1000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);
        assertThat(result.messagesBefore()).isEqualTo(10);
        assertThat(result.messagesAfter()).isEqualTo(2);
        verify(messageStore).save(eq("sess1"), eq("system"), anyList(), eq(true));
    }

    @Test
    void smallContextWindow_lowUsage_noCompression() {
        EnvConfig.init(Map.of(
                "HARNESS_CTX_COMPRESS_MAJOR", "85",
                "HARNESS_CTX_COMPRESS_MAJOR_TARGET", "30"
        ));
        MemoryCompressor smallCompressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);

        List<MemoryMessage> messages = createMessages(5);

        // totalBudget=10000, totalUsed=500 (5%) → no trigger
        var result = smallCompressor.compressIfNeeded("sess1", messages, 500, 500, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.NONE);
        verifyNoInteractions(chatModelProvider);
    }

    @Test
    void fallbackTruncation_respectsTargetSize() {
        // Null chat model → fallback truncation
        when(chatModelProvider.chatModel()).thenReturn(null);
        when(messageStore.loadForContext("sess1")).thenReturn(createMessages(2));

        List<MemoryMessage> messages = createMessages(20);
        // targetTokens = 10000 * 30 / 100 = 3000, targetChars = 9000
        var result = compressor.compressIfNeeded("sess1", messages, 5000, 9000, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(messageStore).save(eq("sess1"), eq("system"), captor.capture(), eq(true));
        String summary = MemoryMessage.text(captor.getValue());
        // Fallback should produce a summary within ~9000 chars
        assertThat(summary.length()).isLessThanOrEqualTo(9000 + 100); // small margin for header
        assertThat(summary).contains("[Conversation summary]");
    }

    @Test
    void secondPassCompression_triggeredWhenTooLong() {
        // First pass returns a very long summary → triggers second pass
        String longSummary = "A".repeat(15000); // exceeds 1.5 * targetChars
        String compressedSummary = "Short summary.";

        ChatResponse firstPass = createMockChatResponse(longSummary);
        ChatResponse secondPass = createMockChatResponse(compressedSummary);

        when(chatModelProvider.chatModel()).thenReturn(chatModel);
        when(chatModel.chat(any(UserMessage.class)))
                .thenReturn(firstPass)
                .thenReturn(secondPass);
        when(messageStore.loadForContext("sess1")).thenReturn(createMessages(2));

        // targetTokens=3000, targetChars=9000, 1.5x=13500. longSummary=15000 > 13500
        var result = compressor.compressIfNeeded("sess1", createMessages(10), 5000, 9000, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);
        // Should have called chat twice (first pass + second pass)
        verify(chatModel, times(2)).chat(any(UserMessage.class));
    }

    @Test
    void chatModelException_usesFallback() {
        when(chatModelProvider.chatModel()).thenReturn(chatModel);
        when(chatModel.chat(any(UserMessage.class))).thenThrow(new RuntimeException("API error"));
        when(messageStore.loadForContext("sess1")).thenReturn(createMessages(2));

        var result = compressor.compressIfNeeded("sess1", createMessages(10), 5000, 9000, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(messageStore).save(eq("sess1"), eq("system"), captor.capture(), eq(true));
        // Should fall back to truncation
        assertThat(MemoryMessage.text(captor.getValue())).contains("[Conversation summary]");
    }

    @Test
    void customThreshold_respected() {
        EnvConfig.init(Map.of(
                "HARNESS_CTX_COMPRESS_MAJOR", "50",
                "HARNESS_CTX_COMPRESS_MAJOR_TARGET", "20"
        ));
        MemoryCompressor customCompressor = new MemoryCompressor(messageStore, sessionStore, chatModelProvider);

        List<MemoryMessage> messages = createMessages(10);
        List<MemoryMessage> compressed = createMessages(1);
        ChatResponse chatResponse = createMockChatResponse("Summary.");

        when(chatModelProvider.chatModel()).thenReturn(chatModel);
        when(chatModel.chat(any(UserMessage.class))).thenReturn(chatResponse);
        when(messageStore.loadForContext("sess1")).thenReturn(compressed);

        // 60% usage > 50% threshold → triggers
        var result = customCompressor.compressIfNeeded("sess1", messages, 3000, 6000, 10000);

        assertThat(result.type()).isEqualTo(MemoryCompressor.CompressionResult.CompressionType.MAJOR);
    }
}
