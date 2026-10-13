package experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Measures API-native usage only, never framework-estimated defaults. */
public final class MeasuredModel implements ChatModel {
    private final ChatModel delegate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ObjectNode> calls = new CopyOnWriteArrayList<>();
    public MeasuredModel(ChatModel delegate) { this.delegate = delegate; }
    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
    public void clear() { calls.clear(); }
    public List<ObjectNode> calls() { return List.copyOf(calls); }
    @Override public ChatResponse call(Prompt prompt) {
        var event = mapper.createObjectNode().put("startedAt", java.time.Instant.now().toString());
        long started = System.nanoTime();
        try {
            var response = delegate.call(prompt);
            Object nativeUsage = response.getMetadata().getUsage().getNativeUsage();
            event.set("usage", nativeUsage == null ? mapper.nullNode() : mapper.valueToTree(nativeUsage));
            return response;
        } catch (RuntimeException exception) {
            event.put("errorType", exception.getClass().getSimpleName());
            throw exception;
        } finally {
            event.put("durationMs", (System.nanoTime() - started) / 1_000_000); calls.add(event);
        }
    }
    public ObjectNode metadata() {
        var result = mapper.createObjectNode().put("llmUsageCallCount", calls.size());
        for (int i = 0; i < calls.size(); i++) {
            JsonNode usage = calls.get(i).path("usage");
            String prefix = "llmUsageCall" + (i + 1);
            result.set(prefix + "InputTokens", observed(usage.path("prompt_tokens")));
            result.set(prefix + "OutputTokens", observed(usage.path("completion_tokens")));
            result.set(prefix + "CachedInputTokens", observed(usage.path("prompt_tokens_details").path("cached_tokens")));
        }
        return result;
    }
    private JsonNode observed(JsonNode value) { return value.isIntegralNumber() && value.asLong() >= 0 ? value : mapper.nullNode(); }
}
