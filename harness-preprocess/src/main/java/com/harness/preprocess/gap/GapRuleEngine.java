package com.harness.preprocess.gap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Tier 1 规则引擎：纯 Java 正则/关键词匹配，耗时 &lt;1ms。
 * <p>
 * 两类规则：
 * <ul>
 *   <li>intercept — 极简拦截（问候、致谢），匹配后短路返回完整 GapAnalysis</li>
 *   <li>force — 强制触发（时效性→webSearch，深度指令→thinking），只设置指定字段</li>
 * </ul>
 * 未命中任何规则时返回 {@link GapAnalysis#defaults()}（全 null），放行到 Tier 2。
 */
public class GapRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(GapRuleEngine.class);

    // ==================== Intercept 规则：匹配后直接短路 ====================
    private static final Pattern INTERCEPT_PATTERN = Pattern.compile(
            "^(你好|hello|hi|hey|嗨|哈喽|谢谢|thanks|thank you|thx|再见|bye|拜拜|88|ok|好的|收到|嗯)$",
            Pattern.CASE_INSENSITIVE
    );

    // ==================== Force 规则：匹配后设置指定字段 ====================
    private static final Pattern WEB_SEARCH_PATTERN = Pattern.compile(
            "(最新|今天|今日|昨天|本周|实时|股价|天气|新闻|价格|汇率|current|latest|today|yesterday|now|live|price|weather|stock)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern THINKING_PATTERN = Pattern.compile(
            "(推导|证明|分析以下|详细分析|深入|复杂|比较.*和.*|评估|论证|design|analyze|prove|derive|evaluate|compare|complex|in-depth)",
            Pattern.CASE_INSENSITIVE
    );

    public GapRuleEngine() {
        log.info("[GapRuleEngine] initialized with {} intercept + {} force rules",
                1, 2); // intercept=1 pattern, force=2 patterns
    }

    /**
     * Tier 1 评估。
     *
     * @param query 用户查询文本
     * @return GapAnalysis，未命中的字段为 null
     */
    public GapAnalysis evaluate(String query) {
        if (query == null || query.isBlank()) {
            return GapAnalysis.defaults();
        }

        String trimmed = query.trim();

        // 1. Intercept：极简查询短路
        if (INTERCEPT_PATTERN.matcher(trimmed).matches()) {
            log.info("[GapRuleEngine] intercept matched: \"{}\" → all false", trimmed);
            return new GapAnalysis(false, false, false, "rule");
        }

        // 2. Force：逐字段强制触发
        Boolean needsWebSearch = null;
        Boolean needsThinking = null;

        if (WEB_SEARCH_PATTERN.matcher(trimmed).find()) {
            needsWebSearch = true;
            log.info("[GapRuleEngine] force webSearch=true: \"{}\"", trimmed);
        }
        if (THINKING_PATTERN.matcher(trimmed).find()) {
            needsThinking = true;
            log.info("[GapRuleEngine] force thinking=true: \"{}\"", trimmed);
        }

        // 未命中任何 force 规则的字段保持 null，放行到 Tier 2
        return new GapAnalysis(null, needsThinking, needsWebSearch, "rule");
    }
}
