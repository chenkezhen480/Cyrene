package com.harness.agent.experiment;

import com.harness.agent.AgentOrchestrator;
import com.harness.core.model.AgentContext;
import com.harness.core.model.AgentResult;
import com.harness.core.model.CancellationToken;
import com.harness.core.model.ThinkingLevel;
import com.harness.tool.HttpApiTool;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExperimentCancellationTest {
    @Test
    void directRunMirrorsTheHttpCredentialBoundaryAndRestoresOuterScopeOnFailure() {
        var agent = mock(AgentOrchestrator.class);
        var credentials = java.util.Map.of("fixtureToken", "synthetic-owner-token");
        var previous = java.util.Map.of("outerToken", "synthetic-outer-token");
        var context = new AgentContext(java.util.Map.of(AgentContext.KEY_CREDENTIALS, credentials));
        when(agent.run(anyString(), anyString(), anyList(), anyString(), isNull(), any(CancellationToken.class),
                eq(ThinkingLevel.OFF), anyString(), same(context))).thenAnswer(invocation -> {
            assertThat(HttpApiTool.getCurrentCredentialsSnapshot()).containsExactlyEntriesOf(credentials);
            throw new IllegalStateException("synthetic failure");
        });
        HttpApiTool.setCurrentCredentials(previous);
        try {
            assertThatThrownBy(() -> CyreneExperimentIT.runCyreneTrial(agent, "synthetic-auth", "query", "session1",
                    ThinkingLevel.OFF, "ownerAlpha", context)).hasMessage("synthetic failure");
            assertThat(HttpApiTool.getCurrentCredentialsSnapshot()).containsExactlyEntriesOf(previous);
        } finally { HttpApiTool.clearCurrentCredentials(); }
    }

    @Test
    void runtimeErrorWithoutRecordedModelCallsStopsAfterItsRowWhileFactFailuresContinue() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var error = mapper.readTree("{\"status\":\"error\",\"condition\":\"wiki_on\",\"taskId\":\"contract-test\",\"error\":\"cancellationToken cannot be null\",\"trace\":{\"metadata\":{}}}");
        assertThatThrownBy(() -> CyreneExperimentIT.requireModelEntry(error))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("contract-test");
        CyreneExperimentIT.requireModelEntry(mapper.readTree("{\"status\":\"completed\",\"passed\":false}"));
        CyreneExperimentIT.requireModelEntry(mapper.readTree("{\"status\":\"error\",\"trace\":{\"metadata\":{\"llmUsageCallCount\":\"1\"}}}"));
    }

    @Test
    void eachRealCyreneInvocationReceivesAnUncancelledIndependentToken() {
        var agent = mock(AgentOrchestrator.class);
        List<CancellationToken> received = new ArrayList<>();
        when(agent.run(anyString(), anyString(), anyList(), anyString(), isNull(), nullable(CancellationToken.class),
                eq(ThinkingLevel.OFF), anyString(), any(AgentContext.class))).thenAnswer(invocation -> {
            CancellationToken token = invocation.getArgument(5);
            assertThat(token).as("Agent run scope requires a cancellation token").isNotNull();
            assertThat(token.isCancelled()).isFalse();
            received.add(token);
            return mock(AgentResult.class);
        });
        var context = new AgentContext(java.util.Map.of());
        CyreneExperimentIT.runCyreneTrial(agent, "synthetic-auth", "query", "session1", ThinkingLevel.OFF, "ownerAlpha", context);
        received.getFirst().cancel();
        CyreneExperimentIT.runCyreneTrial(agent, "synthetic-auth", "query", "session2", ThinkingLevel.OFF, "ownerAlpha", context);
        assertThat(received.getFirst()).isNotSameAs(received.getLast());
    }
}
