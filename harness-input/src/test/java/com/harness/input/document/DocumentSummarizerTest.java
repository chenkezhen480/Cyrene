package com.harness.input.document;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.AgentException;
import com.harness.core.text.UnicodeAwareTextTokenEstimator;
import com.harness.provider.ChatModelProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentSummarizerTest {
    private final List<ChatRequest> requests = new ArrayList<>();
    private int contextWindow = 10_000;
    private Function<ChatRequest, ChatResponse> response = request -> ChatResponse.builder()
            .aiMessage(AiMessage.from("document summary")).build();
    private final ChatModel model = new ChatModel() {
        @Override public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            return response.apply(request);
        }
    };
    private final ChatModelProvider provider = new ChatModelProvider() {
        @Override public ChatModel chatModel() { return model; }
        @Override public String providerName() { return "test"; }
        @Override public String modelName() { return "primary-model"; }
        @Override public int contextWindow() { return contextWindow; }
        @Override public dev.langchain4j.model.chat.request.ChatRequestParameters planningRequestParameters(
                com.harness.core.model.ThinkingLevel thinkingLevel, List<dev.langchain4j.agent.tool.ToolSpecification> tools) {
            assertThat(thinkingLevel).isEqualTo(com.harness.core.model.ThinkingLevel.OFF);
            assertThat(tools).isEmpty();
            return dev.langchain4j.model.chat.request.ChatRequestParameters.builder().temperature(0.2).build();
        }
    };
    private final DocumentSummarizer summarizer = new DocumentSummarizer(
            () -> provider, UnicodeAwareTextTokenEstimator.INSTANCE);

    @BeforeEach
    void setUp() {
        EnvConfig.init(Map.of(EnvKey.LARGE_FILE_CONTEXT_RATIO, "0.6",
                EnvKey.LARGE_FILE_SUMMARY_CONCURRENCY, "1"));
    }

    @Test
    void fittingDocumentUsesOneIsolatedRequestWithNoHistoryOrTools() {
        String markdown = "# Document\n\n" + "资料数据".repeat(1000);
        var result = summarizer.summarize(markdown, "Describe document coverage", 256);
        assertThat(result.calls()).isEqualTo(1);
        assertThat(result.inputBlocks()).isEqualTo(1);
        assertThat(result.model()).isEqualTo("primary-model");
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().messages()).hasSize(2);
        assertThat(requests.getFirst().messages().getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) requests.getFirst().messages().getLast()).singleText()).isEqualTo(markdown);
        assertThat(requests.getFirst().parameters().toolSpecifications()).isNullOrEmpty();
        assertThat(requests.getFirst().parameters().maxOutputTokens()).isEqualTo(256);
        assertThat(requests.getFirst().parameters().temperature()).isEqualTo(0.2);
    }

    @Test
    void oversizedDocumentUsesContextSizedBatchesThenOneMerge() {
        var result = summarizer.summarize("资料数据".repeat(3000), "Read facts", 256);
        assertThat(result.inputBlocks()).isEqualTo(3);
        assertThat(result.calls()).isEqualTo(4);
        assertThat(requests).hasSize(4);
        assertRequestsFitBudget();
        assertThat(((UserMessage) requests.getLast().messages().getLast()).singleText())
                .isEqualTo("document summary\n\ndocument summary\n\ndocument summary");
        assertThat(requests.subList(0, 3).stream()
                .map(request -> ((UserMessage) request.messages().getLast()).singleText().replace("\n", ""))
                .reduce("", String::concat)).isEqualTo("资料数据".repeat(3000));
    }

    @Test
    void laterOperationsUseTheUpdatedPrimaryModelContextWindow() {
        String markdown = "资料数据".repeat(2200);
        assertThat(summarizer.summarize(markdown, "Read facts", 256).calls()).isEqualTo(3);
        contextWindow = 20_000;
        requests.clear();
        assertThat(summarizer.summarize(markdown, "Read facts", 256).calls()).isEqualTo(1);
    }

    @Test
    void mergeIsAlsoBatchedWhenIntermediateSummariesExceedTheBudget() {
        contextWindow = 1000;
        response = request -> ChatResponse.builder().aiMessage(AiMessage.from(
                "摘".repeat(request.parameters().maxOutputTokens()))).build();
        var result = summarizer.summarize("资料数据".repeat(2000), "Read facts", 64);
        assertThat(result.calls()).isGreaterThan(result.inputBlocks() + 1);
        assertRequestsFitBudget();
        assertThat(UnicodeAwareTextTokenEstimator.INSTANCE.estimate(result.text())).isLessThanOrEqualTo(64);
    }

    @Test
    void rejectsInvalidBudgetsAndEmptyOrTruncatedModelOutput() {
        EnvConfig.init(Map.of(EnvKey.LARGE_FILE_CONTEXT_RATIO, "1"));
        assertThatThrownBy(() -> summarizer.summarize("body", "task", 256))
                .isInstanceOf(IllegalArgumentException.class);
        setUp();
        response = request -> ChatResponse.builder().aiMessage(AiMessage.from(" ")).build();
        assertThatThrownBy(() -> summarizer.summarize("body", "task", 256))
                .isInstanceOf(AgentException.class).hasMessageContaining("empty");
        response = request -> ChatResponse.builder().aiMessage(AiMessage.from("cut off"))
                .finishReason(FinishReason.LENGTH).build();
        assertThatThrownBy(() -> summarizer.summarize("body", "task", 256))
                .isInstanceOf(AgentException.class).hasMessageContaining("truncated");
        response = request -> ChatResponse.builder().aiMessage(AiMessage.from("摘".repeat(257))).build();
        assertThatThrownBy(() -> summarizer.summarize("body", "task", 256))
                .isInstanceOf(AgentException.class).hasMessageContaining("output token budget");
    }

    @Test
    void reportedOutputTokensTakePrecedenceOverConservativeChineseEstimates() {
        response = request -> ChatResponse.builder().aiMessage(AiMessage.from("摘要".repeat(200)))
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(10, 240)).build();
        assertThat(summarizer.summarize("body", "task", 256).text()).hasSize(400);
        response = request -> ChatResponse.builder().aiMessage(AiMessage.from("short"))
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(10, 257)).build();
        assertThatThrownBy(() -> summarizer.summarize("body", "task", 256))
                .isInstanceOf(AgentException.class).hasMessageContaining("output token budget");
    }

    private void assertRequestsFitBudget() {
        for (ChatRequest request : requests) {
            int tokens = request.messages().stream().mapToInt(message -> {
                String text = message instanceof SystemMessage system ? system.text() : ((UserMessage) message).singleText();
                return UnicodeAwareTextTokenEstimator.INSTANCE.estimate(text);
            }).sum();
            assertThat(tokens + 16).isLessThanOrEqualTo((int) (contextWindow * 0.6));
            assertThat(tokens + request.parameters().maxOutputTokens() + 32).isLessThanOrEqualTo(contextWindow);
        }
    }
}
