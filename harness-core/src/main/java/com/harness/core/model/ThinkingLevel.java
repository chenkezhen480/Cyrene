package com.harness.core.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 五档思考强度，替代原先两态的 {@code enableThinking} 开关。
 *
 * <p>档位本身协议无关；下发哪个 HTTP 参数由 provider 层按方言映射
 * （effort 方言 → 顶层 {@code reasoning_effort}，qwen 方言 →
 * {@code enable_thinking}，可选叠加 {@code thinking_budget}）。
 * {@code null}（请求未指定）保留原语义：回退 GapAnalysis 漏斗与模型级默认。</p>
 */
public enum ThinkingLevel {
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");

    private final String configValue;

    ThinkingLevel(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    /** 严格解析，未知值抛出并列出可选值（配置校验与 400 响应用）。 */
    public static ThinkingLevel parse(String value) {
        ThinkingLevel level = parseNullable(value);
        if (level == null) {
            throw new IllegalArgumentException(
                    "Invalid thinking level '" + value + "'. Allowed values: " + allowedValues());
        }
        return level;
    }

    /** 宽松解析，null/空白/未知值返回 null（请求级语义：未指定 → 回退默认档）。 */
    public static ThinkingLevel parseNullable(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        return Arrays.stream(values())
                .filter(level -> level.configValue.equals(normalized))
                .findFirst()
                .orElse(null);
    }

    public static String allowedValues() {
        return Arrays.stream(values())
                .map(ThinkingLevel::configValue)
                .collect(Collectors.joining(", "));
    }
}
