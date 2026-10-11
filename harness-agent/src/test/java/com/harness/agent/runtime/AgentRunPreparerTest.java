package com.harness.agent.runtime;

import com.harness.input.gap.GapAnalysis;
import com.harness.core.model.AgentContext;
import com.harness.core.model.AgentTrace;
import com.harness.core.security.RequestPrincipal;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.runtime.RunTrace;
import com.harness.agent.context.AgentPromptBuilder;
import com.harness.agent.lifecycle.AgentLifecycleHooks;
import com.harness.agent.memory.AgentMemoryRuntime;
import com.harness.agent.memory.PreferenceActivationContextBuilder;
import com.harness.input.InputProcessor;
import com.harness.input.auth.Authenticator;
import com.harness.input.multimodal.MultimodalParser;
import com.harness.provider.ModelProviders;
import com.harness.provider.ChatModelProvider;
import com.harness.react.ReActLoopFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


class AgentRunPreparerTest {

    @Test
    void trustedServiceUserOwnsPreparedMemoryAndTrace() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "token", EnvKey.AUTH_TOKEN, "service-token"));
        var input = new InputProcessor(new Authenticator(), mock(MultimodalParser.class));
        var providers = mock(ModelProviders.class);
        var chat = mock(ChatModelProvider.class);
        when(providers.chat()).thenReturn(chat);
        var trace = mock(RunTrace.class);
        when(trace.snapshot()).thenReturn(AgentTrace.builder().build());
        var memory = mock(AgentMemoryRuntime.class);
        when(memory.resolve(any(), any(), any(), any(), any(), any())).thenAnswer(call ->
                new AgentMemoryRuntime.MemoryContext("session", call.getArgument(0), call.getArgument(1), List.of()));
        var prompt = mock(AgentPromptBuilder.class);
        when(prompt.enhanceUserText(any(), any(), any(), any())).thenReturn("hello");
        var runtime = new AgentRuntime(providers, input, mock(ReActLoopFactory.class), () -> trace);
        var hooks = new AgentLifecycleHooks(List.of(context -> context.withGapAnalysis(GapAnalysis.defaults())), List.of());
        var preparer = new AgentRunPreparer(runtime, prompt, hooks, memory, false, null,
                mock(PreferenceActivationContextBuilder.class));
        var principal = new RequestPrincipal("verified-user", "tenant-a", "reader", RequestPrincipal.AuthenticationType.SERVICE_TOKEN);
        var context = new AgentContext(Map.of("userId", "forged-user", "tenantId", "tenant-a"), principal);
        Consumer<String> beforeHistoryLoad = mock(Consumer.class);
        var prepared = preparer.prepare(new AgentRunPreparer.AgentRunRequest(
                "service-token", "hello", List.of(), null, null, "forged-user", context, false), trace, beforeHistoryLoad);
        assertThat(prepared.userId()).isEqualTo("verified-user");
        verify(memory).resolve("verified-user", "tenant-a", null, "hello", trace, beforeHistoryLoad);
        verify(trace).recordInput("verified-user", "hello", List.of());
        verify(trace).setSessionId("session");
        assertThat(context.withToolDenylist(java.util.Set.of("web.search")).principal()).isSameAs(principal);
        var sdkInput = preparer.prepare(new AgentRunPreparer.AgentRunRequest(
                "service-token", "hello", List.of(), null, null, "forged-user",
                AgentContext.of(Map.of("userId", "forged-user", "tenantId", "tenant-a")), false), trace, beforeHistoryLoad);
        assertThat(sdkInput.userId()).isEqualTo("token-user");
    }

    @Test
    void resolvesIntentFromGapAnalysis() {
        assertThat(AgentRunPreparer.resolveIntent(null)).isEqualTo("chat");
        assertThat(AgentRunPreparer.resolveIntent(GapAnalysis.defaults())).isEqualTo("chat");

        assertThat(AgentRunPreparer.resolveIntent(
                new GapAnalysis(true, false, false, "rule"))).isEqualTo("knowledge_search");

        assertThat(AgentRunPreparer.resolveIntent(
                new GapAnalysis(false, false, true, "rule"))).isEqualTo("web_search");

        assertThat(AgentRunPreparer.resolveIntent(
                new GapAnalysis(true, false, true, "rule"))).isEqualTo("knowledge_and_web_search");

        assertThat(AgentRunPreparer.resolveIntent(
                new GapAnalysis(false, true, false, "rule"))).isEqualTo("reasoning");

        assertThat(AgentRunPreparer.resolveIntent(
                new GapAnalysis(false, false, false, "rule"))).isEqualTo("chat");
    }
}
