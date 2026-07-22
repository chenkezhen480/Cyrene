package com.harness.preprocess.gap;

import com.harness.core.model.AgentContext;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gap 分析式动态路由：根据查询特征决定 thinking/retrieval/rewrite/webSearch 四个参数。
 * <p>
 * 三级判定漏斗：
 * <ol>
 *   <li>显式覆盖 — 客户端在 context 里传了就直接用</li>
 *   <li>规则引擎（Tier 1）— 纯 Java 正则/关键词匹配，&lt;1ms</li>
 *   <li>LLM 分类（Tier 2）— 调用分类模型兜底</li>
 * </ol>
 * <p>
 * 每级只填充上一级为 null 的字段。四级全部确定后短路返回。
 */
public class GapAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GapAnalyzer.class);
    private final boolean enabled;
    private final GapRuleEngine ruleEngine;
    private final GapClassifier classifier;

    public GapAnalyzer(GapRuleEngine ruleEngine, GapClassifier classifier) {
        this.enabled = EnvConfig.get().getBool(EnvKey.GAP_ANALYSIS_ENABLED, true);
        this.ruleEngine = ruleEngine;
        this.classifier = classifier;
        log.info("[GapAnalyzer] enabled={}, ruleEngine={}, classifier={}",
                enabled, ruleEngine != null, classifier != null);
    }

    /**
     * 分析查询，返回 GapAnalysis。
     * <p>
     * 三级漏斗：显式覆盖 → 规则引擎 → LLM 分类，每级只填 null 字段。
     *
     * @param query   用户查询文本
     * @param context 请求上下文（含显式覆盖字段）
     * @return GapAnalysis 四个独立字段
     */
    public GapAnalysis analyze(String query, AgentContext context) {
        if (!enabled) {
            return GapAnalysis.defaults();
        }

        // Tier 0: 显式覆盖
        GapAnalysis explicit = GapAnalysis.from(
                context.needsKnowledgeBase(),
                context.enableThinking(),
                context.needsWebSearch()
        );
        if (explicit.isComplete()) {
            log.info("[GapAnalyzer] query=\"{}\" → source=explicit, result={}", truncate(query, 50), explicit);
            return explicit;
        }

        // Tier 1: 规则引擎
        GapAnalysis ruleResult = ruleEngine.evaluate(query);
        GapAnalysis merged = GapAnalysis.merge(explicit, ruleResult);
        if (merged.isComplete()) {
            log.info("[GapAnalyzer] query=\"{}\" → source=rule, result={}", truncate(query, 50), merged);
            return merged;
        }

        // Tier 2: LLM 分类（仅填充仍为 null 的字段）
        GapAnalysis llmResult = classifier.classify(query);
        GapAnalysis final_ = GapAnalysis.merge(merged, llmResult);

        log.info("[GapAnalyzer] query=\"{}\" → source={}, result={}", truncate(query, 50), final_.source(), final_);
        return final_;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
