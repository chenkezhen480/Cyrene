package com.harness.agent.runtime;

import com.harness.input.gap.GapAnalysis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunPreparerTest {

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
