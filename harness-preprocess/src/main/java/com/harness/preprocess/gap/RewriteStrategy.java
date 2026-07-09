package com.harness.preprocess.gap;

/**
 * 查询改写策略枚举。
 * 对应 QueryRewriter 的四种策略，供 GapAnalyzer 输出和 AgentContext 传递。
 */
public enum RewriteStrategy {
    /** 不改写，直接用原始查询检索 */
    NONE,
    /** 假设性文档嵌入：LLM 生成"假答案"，用假答案去向量检索 */
    HYDE,
    /** 多查询改写：LLM 生成多个不同措辞的查询，分别检索后合并 */
    MULTI_QUERY,
    /** 抽象化改写：LLM 生成更通用的查询，先获取背景知识 */
    STEP_BACK;

    /**
     * 从字符串解析策略名（忽略大小写），无效值返回 null。
     */
    public static RewriteStrategy fromString(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
