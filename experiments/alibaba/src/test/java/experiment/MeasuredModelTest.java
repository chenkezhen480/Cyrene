package experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MeasuredModelTest {
    @Test void missingNativeUsageIsUnknownDespiteDefaultZeros() {
        ChatModel fake = p -> ChatResponse.builder().generations(List.of()).metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0)).build()).build();
        var measured = new MeasuredModel(fake);
        measured.call(new Prompt("test"));
        assertEquals(1, measured.calls().size());
        assertTrue(measured.metadata().get("llmUsageCall1InputTokens").isNull());
        assertTrue(measured.metadata().get("llmUsageCall1OutputTokens").isNull());
    }
    @Test void observedNativeUsageIsPreserved() {
        var nativeUsage = Map.of("prompt_tokens", 23, "completion_tokens", 7, "prompt_tokens_details", Map.of("cached_tokens", 11));
        ChatModel fake = p -> ChatResponse.builder().generations(List.of()).metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(23, 7, 30, nativeUsage)).build()).build();
        var measured = new MeasuredModel(fake); measured.call(new Prompt("test"));
        assertEquals(23, measured.metadata().path("llmUsageCall1InputTokens").asInt());
        assertEquals(7, measured.metadata().path("llmUsageCall1OutputTokens").asInt());
        assertEquals(11, measured.metadata().path("llmUsageCall1CachedInputTokens").asInt());
    }
    @Test void failedCallRemainsRecordedWithoutUsage() {
        var measured = new MeasuredModel(p -> { throw new IllegalStateException("model failed"); });
        assertThrows(IllegalStateException.class, () -> measured.call(new Prompt("test")));
        assertEquals(1, measured.metadata().path("llmUsageCallCount").asInt());
        assertTrue(measured.calls().getFirst().has("errorType"));
        assertTrue(measured.metadata().path("llmUsageCall1InputTokens").isNull());
    }
}
