package com.harness.input.gap;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.AgentContext;
import com.harness.core.model.ThinkingLevel;
import com.harness.provider.RoutingModelProvider;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JevGapRoutingTest {
    @Test
    void preservesJevLevelAndExplicitOverridesWithoutKeywordShortCircuit() {
        EnvConfig.init(Map.of(EnvKey.GAP_ANALYSIS_ENABLED, "true"));
        var provider = mock(RoutingModelProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.route(anyString())).thenReturn(
                new RoutingModelProvider.Decision(ThinkingLevel.XHIGH, true, false));
        var analyzer = new GapAnalyzer(new GapRuleEngine(), new GapModelAnalyzer(provider));
        var result = analyzer.analyze("hello", AgentContext.empty());
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.XHIGH);
        assertThat(result.source()).isEqualTo("jev");
        result = analyzer.analyze("architecture", AgentContext.of(Map.of(
                "thinkingLevel", "low", "needsWebSearch", true)));
        assertThat(result.thinkingLevel()).isEqualTo(ThinkingLevel.LOW);
        assertThat(result.needsWebSearch()).isTrue();
        assertThat(result.needsKnowledgeBase()).isTrue();
        analyzer.analyze("explicit", AgentContext.of(Map.of(
                "thinkingLevel", "off", "needsWebSearch", false, "needsKnowledgeBase", false)));
        verify(provider, never()).route("explicit");
        when(provider.route("failure")).thenThrow(new IllegalStateException("JEV unavailable"));
        assertThatThrownBy(() -> analyzer.analyze("failure", AgentContext.empty()))
                .hasMessage("JEV unavailable");
    }
}
