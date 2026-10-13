package com.harness.agent.experiment;

import com.harness.core.env.EnvConfig;
import com.harness.core.model.ThinkingLevel;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.gap.GapAnalyzer;
import com.harness.input.gap.GapModelAnalyzer;
import com.harness.input.gap.GapRuleEngine;
import com.harness.provider.RoutingModelProvider;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ExperimentRoutingPolicyTest {
    @Test
    void businessOnlyCompletesTierZeroWithoutKnowledgeOrRouting() {
        EnvConfig.init(Map.of("HARNESS_GAP_ANALYSIS_ENABLED", "true"));
        var routing = mock(RoutingModelProvider.class);
        var context = CyreneExperimentIT.businessTrialContext(new RequestPrincipal("ownerAlpha", "fixtureTenant", "OWNER",
                RequestPrincipal.AuthenticationType.JWT), "synthetic-token", "isolated_docs", Set.of("knowledge_search", "knowledge_read"), ThinkingLevel.OFF);
        var result = new GapAnalyzer(new GapRuleEngine(), new GapModelAnalyzer(routing)).analyze("查询我的客户", context);
        assertThat(result.source()).isEqualTo("explicit");
        assertThat(result.isComplete()).isTrue();
        assertThat(result.needsKnowledgeBase()).isFalse();
        assertThat(result.needsWebSearch()).isFalse();
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.OFF);
        assertThat(context.credentials()).containsEntry("fixtureToken", "synthetic-token");
        verifyNoInteractions(routing);
    }

    @Test
    void actualTrialContextCompletesTierZeroWithoutCallingConfiguredRouting() {
        EnvConfig.init(Map.of("HARNESS_GAP_ANALYSIS_ENABLED", "true"));
        var routing = mock(RoutingModelProvider.class);
        var context = CyreneExperimentIT.trialContext(new RequestPrincipal("ownerAlpha", "fixtureTenant", "OWNER",
                RequestPrincipal.AuthenticationType.JWT), "synthetic-token", "isolated_docs", Set.of("unrelated_tool"), ThinkingLevel.OFF);
        var result = new GapAnalyzer(new GapRuleEngine(), new GapModelAnalyzer(routing)).analyze("查询当前政策", context);
        assertThat(result.source()).isEqualTo("explicit");
        assertThat(result.isComplete()).isTrue();
        assertThat(result.needsKnowledgeBase()).isTrue();
        assertThat(result.needsWebSearch()).isFalse();
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.OFF);
        assertThat(context.knowledgeRequestContext().collection()).isEqualTo("isolated_docs");
        verifyNoInteractions(routing);
    }
}
