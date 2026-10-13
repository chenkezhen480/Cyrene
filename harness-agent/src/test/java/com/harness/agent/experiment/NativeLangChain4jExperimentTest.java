package com.harness.agent.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.agent.context.KnowledgeAccessService;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.env.EnvConfig;
import com.harness.core.model.*;
import com.harness.core.security.RequestPrincipal;
import com.harness.provider.ChatModelProvider;
import com.harness.tool.HttpApiTool;
import com.harness.tool.Tool;
import com.harness.tool.ToolExecutor;
import com.harness.tool.ToolRegistry;
import com.harness.tool.confirmation.ConfirmationManager;
import com.harness.trace.TraceCollector;
import com.harness.trace.store.TraceStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NativeLangChain4jExperimentTest {
    @Test
    void nativeLoopActivatesTrustedScopeForCallbacksAndAccountsAllModelCalls() throws Exception {
        EnvConfig.init(Map.of("HARNESS_RISK_CONFIRM_TOOLS", ""));
        var mapper = new ObjectMapper();
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolSpec spec() { return new ToolSpec("inspect_scope", "Read scope", mapper.createObjectNode().put("type", "object")); }
            @Override public String execute(JsonNode args) {
                var scope = KnowledgeToolRuntimeContext.requireCurrent("inspect_scope");
                assertThat(scope.authorizedTools()).containsExactly("inspect_scope");
                assertThat(KnowledgeAccessService.captureCurrentContext().requestContext().collection()).isEqualTo("isolated_docs");
                return scope.userId() + ":" + HttpApiTool.getCurrentCredentialsSnapshot().get("fixtureToken");
            }
        });
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                int call = calls.incrementAndGet();
                assertThat(request.toolSpecifications()).extracting(dev.langchain4j.agent.tool.ToolSpecification::name).containsExactly("inspect_scope");
                AiMessage message = call == 1 ? AiMessage.from(ToolExecutionRequest.builder()
                        .id("call1").name("inspect_scope").arguments("{}").build()) : AiMessage.from(request.messages().stream()
                        .filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast).findFirst().orElseThrow().text());
                return ChatResponse.builder().aiMessage(message).tokenUsage(new TokenUsage(call == 1 ? 10 : 20, call == 1 ? 2 : 3))
                        .finishReason(call == 1 ? FinishReason.TOOL_EXECUTION : FinishReason.STOP).build();
            }
        };
        ChatModelProvider provider = new ChatModelProvider() {
            @Override public ChatModel chatModel() { return model; }
            @Override public String providerName() { return "local-test"; }
            @Override public String modelName() { return "local-test"; }
        };
        var context = new AgentContext(Map.of(AgentContext.KEY_CREDENTIALS, Map.of("fixtureToken", "synthetic-token"),
                AgentContext.KEY_KNOWLEDGE_REQUEST_CONTEXT, Map.of("collection", "isolated_docs", "allowedDocumentIds", Set.of())),
                new RequestPrincipal("ownerAlpha", "fixtureTenant", "OWNER", RequestPrincipal.AuthenticationType.JWT));
        HttpApiTool.setCurrentCredentials(Map.of("previous", "sentinel"));
        try {
            var result = NativeLangChain4jExperiment.run(provider, registry.snapshot(),
                    new ToolExecutor(new ConfirmationManager(Duration.ofSeconds(1))), context,
                    new TraceCollector(mock(TraceStore.class)), "Inspect authorized scope", "inspect", ThinkingLevel.OFF, 3);
            assertThat(result.output()).isEqualTo("ownerAlpha:synthetic-token");
            assertThat(result.trace().totalTokens()).isEqualTo(35);
            assertThat(result.trace().metadata().get("llmUsageCallCount")).isEqualTo("2");
            assertThat(result.steps()).hasSize(1);
            assertThat(HttpApiTool.getCurrentCredentialsSnapshot()).containsExactlyEntriesOf(Map.of("previous", "sentinel"));
            assertThat(KnowledgeToolRuntimeContext.captureCurrent()).isNull();
            assertThat(KnowledgeAccessService.captureCurrentContext()).isNull();
        } finally { HttpApiTool.clearCurrentCredentials(); }
    }

    @Test
    void nativeResultPreservesPendingConfirmationWithoutExecutingTheMutation() {
        EnvConfig.init(Map.of("HARNESS_RISK_CONFIRM_TOOLS", ""));
        var registry = new ToolRegistry();
        var executed = new java.util.concurrent.atomic.AtomicBoolean();
        registry.register(new Tool() {
            @Override public ToolSpec spec() { return new ToolSpec("mutation", "Write", new ObjectMapper().createObjectNode().put("type", "object"),
                    Set.of(ToolCapability.MUTATION.tag()), true); }
            @Override public String execute(JsonNode args) { executed.set(true); return "written"; }
        });
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                boolean first = calls.incrementAndGet() == 1;
                return ChatResponse.builder().aiMessage(first ? AiMessage.from(ToolExecutionRequest.builder()
                        .id("pending").name("mutation").arguments("{}").build()) : AiMessage.from("Please confirm"))
                        .tokenUsage(new TokenUsage(1, 1)).finishReason(first ? FinishReason.TOOL_EXECUTION : FinishReason.STOP).build();
            }
        };
        ChatModelProvider provider = new ChatModelProvider() {
            @Override public ChatModel chatModel() { return model; }
            @Override public String providerName() { return "local-test"; }
            @Override public String modelName() { return "local-test"; }
        };
        var context = new AgentContext(Map.of(), new RequestPrincipal("ownerAlpha", "fixtureTenant", "OWNER", RequestPrincipal.AuthenticationType.JWT));
        var result = NativeLangChain4jExperiment.run(provider, registry.snapshot(), new ToolExecutor(new ConfirmationManager(Duration.ofSeconds(1))),
                context, new TraceCollector(mock(TraceStore.class)), "Confirm mutations", "write", ThinkingLevel.OFF, 3);
        assertThat(executed).isFalse();
        assertThat(result.requiresConfirmation()).isTrue();
        assertThat(result.trace().userConfirmed()).isFalse();
    }
}
