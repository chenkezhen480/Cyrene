package com.harness.input.gap;

import com.harness.core.model.AgentContext;
import com.harness.core.model.ThinkingLevel;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit request choices take precedence over dedicated JEV routing. */
public class GapAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GapAnalyzer.class);
    private final boolean enabled;
    private final GapRuleEngine ruleEngine;
    private final GapModelAnalyzer modelAnalyzer;

    public GapAnalyzer(GapRuleEngine ruleEngine, GapModelAnalyzer modelAnalyzer) {
        this.enabled = EnvConfig.get().getBool(EnvKey.GAP_ANALYSIS_ENABLED, true);
        this.ruleEngine = ruleEngine;
        this.modelAnalyzer = modelAnalyzer;
        log.info("[GapAnalyzer] enabled={}, ruleEngine={}, routingModel={}",
                enabled, ruleEngine != null, modelAnalyzer != null);
    }

    public GapAnalysis analyze(String query, AgentContext context) {
        if (!enabled) {
            return GapAnalysis.defaults();
        }

        // Tier 0: 显式覆盖（thinkingLevel 兼容折叠旧 enableThinking，档位映射为布尔判定）
        ThinkingLevel thinkingLevel = context.thinkingLevel();
        GapAnalysis explicit = new GapAnalysis(
                context.needsKnowledgeBase(),
                thinkingLevel == null ? null : thinkingLevel != ThinkingLevel.OFF,
                context.needsWebSearch(), "explicit", thinkingLevel
        );
        if (explicit.isComplete()) {
            log.info("[GapAnalyzer] query=\"{}\" → source=explicit, result={}", truncate(query, 50), explicit);
            return explicit;
        }

        if (modelAnalyzer.isAvailable()) {
            return GapAnalysis.merge(explicit, modelAnalyzer.infer(query));
        }

        // Without a configured routing model, retain deterministic rules.
        GapAnalysis ruleResult = ruleEngine.evaluate(query);
        GapAnalysis merged = GapAnalysis.merge(explicit, ruleResult);
        if (merged.isComplete()) {
            log.info("[GapAnalyzer] query=\"{}\" → source=rule, result={}", truncate(query, 50), merged);
            return merged;
        }

        return merged;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
