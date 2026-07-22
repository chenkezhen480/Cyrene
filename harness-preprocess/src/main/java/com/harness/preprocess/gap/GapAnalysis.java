package com.harness.preprocess.gap;

/**
 * GapAnalyzer 的分析结果，三个独立字段各自生效，不互斥。
 * <p>null 表示未指定，回退到环境变量默认值；显式值优先于环境变量。
 *
 * @param needsKnowledgeBase 是否检索知识库
 * @param needsThinking      是否启用深度思考
 * @param needsWebSearch     是否联网搜索
 * @param source             判定来源：explicit / rule / llm / default
 */
public record GapAnalysis(
        Boolean needsKnowledgeBase,
        Boolean needsThinking,
        Boolean needsWebSearch,
        String source
) {
    /** 全部未指定的默认实例，所有字段回退环境变量 */
    public static GapAnalysis defaults() {
        return new GapAnalysis(null, null, null, "default");
    }

    /** 从 AgentContext 的显式覆盖字段构建，未指定的字段为 null */
    public static GapAnalysis from(Boolean needsKnowledgeBase,
                                   Boolean needsThinking, Boolean needsWebSearch) {
        return new GapAnalysis(
                needsKnowledgeBase,
                needsThinking,
                needsWebSearch,
                "explicit"
        );
    }

    /** 三个字段是否全部非 null（无需再走下一级判定） */
    public boolean isComplete() {
        return needsKnowledgeBase != null
                && needsThinking != null && needsWebSearch != null;
    }

    /**
     * 合并两个 GapAnalysis，higher 优先，只用 lower 填充 higher 中为 null 的字段。
     * source 取 higher 的值。
     */
    public static GapAnalysis merge(GapAnalysis higher, GapAnalysis lower) {
        if (higher == null) return lower;
        if (lower == null) return higher;
        return new GapAnalysis(
                higher.needsKnowledgeBase != null ? higher.needsKnowledgeBase : lower.needsKnowledgeBase,
                higher.needsThinking != null ? higher.needsThinking : lower.needsThinking,
                higher.needsWebSearch != null ? higher.needsWebSearch : lower.needsWebSearch,
                higher.source
        );
    }
}
