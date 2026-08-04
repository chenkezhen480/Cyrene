package com.harness.agent.runtime;

import com.harness.core.runtime.RunTrace;
import com.harness.core.runtime.RunTraceFactory;
import com.harness.input.InputStage;
import com.harness.provider.ChatModelProvider;
import com.harness.provider.ClassifierModelProvider;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.provider.ModelProviders;
import com.harness.provider.RealtimeModelProvider;
import com.harness.provider.RerankModelProvider;
import com.harness.provider.VisionModelProvider;
import com.harness.provider.VoiceModelProvider;
import com.harness.react.ReActLoop;
import com.harness.react.ReActLoopFactory;
import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeTest {

    @Test
    void delegatesRequestScopedLoopAndTraceCreation() {
        ModelProviders providers = new ModelProviders(
                mock(ChatModelProvider.class),
                mock(VisionModelProvider.class),
                mock(VoiceModelProvider.class),
                mock(EmbeddingModelProvider.class),
                mock(RerankModelProvider.class),
                mock(RealtimeModelProvider.class),
                mock(ClassifierModelProvider.class));
        ReActLoopFactory loopFactory = mock(ReActLoopFactory.class);
        RunTraceFactory traceFactory = mock(RunTraceFactory.class);
        ToolCatalog catalog = mock(ToolCatalog.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        ReActLoop loop = mock(ReActLoop.class);
        RunTrace trace = mock(RunTrace.class);
        when(loopFactory.create(catalog, executor)).thenReturn(loop);
        when(traceFactory.start()).thenReturn(trace);

        AgentRuntime runtime = new AgentRuntime(
                providers, mock(InputStage.class), loopFactory, traceFactory);

        assertThat(runtime.createLoop(catalog, executor)).isSameAs(loop);
        assertThat(runtime.startTrace()).isSameAs(trace);
        verify(loopFactory).create(catalog, executor);
        verify(traceFactory).start();
    }
}
