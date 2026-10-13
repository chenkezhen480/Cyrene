package com.harness.agent.experiment;

import com.harness.core.model.AgentTrace;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ExperimentUsageTest {
    @Test
    void derivesTotalOnlyFromCompletePerCallInputAndOutputCoverage() {
        var complete = AgentTrace.builder().totalTokens(0).metadata(Map.of("llmUsageCallCount", "2",
                "llmUsageCall1InputTokens", "7", "llmUsageCall1OutputTokens", "3",
                "llmUsageCall2InputTokens", "11", "llmUsageCall2OutputTokens", "5")).build();
        assertThat(ExperimentUsage.observedTotalTokens(complete)).isEqualTo(26);
        var partial = AgentTrace.builder().totalTokens(200).metadata(Map.of("llmUsageCallCount", "2",
                "llmUsageCall1InputTokens", "7", "llmUsageCall1OutputTokens", "3", "llmUsageCall2InputTokens", "11")).build();
        assertThat(ExperimentUsage.observedTotalTokens(partial)).isNull();
        assertThat(ExperimentUsage.observedTotalTokens(AgentTrace.builder().build())).isNull();
    }
}
