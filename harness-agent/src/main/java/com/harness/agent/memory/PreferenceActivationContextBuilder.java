package com.harness.agent.memory;

import com.harness.core.knowledge.PreferenceActivationContext;
import com.harness.core.knowledge.PreferenceActivationTagRegistry;
import com.harness.input.gap.GapAnalysis;
import com.harness.tool.RunToolCatalog;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Builds only allowlisted activation tags from request intent and immutable tool capabilities. */
public final class PreferenceActivationContextBuilder {

    public PreferenceActivationContext build(
            String userMessage,
            GapAnalysis gapAnalysis,
            RunToolCatalog toolCatalog
    ) {
        if (toolCatalog == null) throw new IllegalArgumentException("toolCatalog is required");
        String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        Set<String> toolNames = toolCatalog.getAll().stream()
                .map(spec -> spec.name()).collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (toolNames.contains("python_sandbox")
                && containsAny(text, "code", "coding", "java", "python", "代码", "编程")) {
            tags.add(PreferenceActivationTagRegistry.CODE_TASK);
        }
        if (toolNames.contains("image_generation")
                && containsAny(text, "image", "picture", "illustration", "图片", "图像", "插画")) {
            tags.add(PreferenceActivationTagRegistry.IMAGE_TASK);
        }
        if (containsAny(text, "report", "analysis", "报告", "汇报", "分析")) {
            tags.add(PreferenceActivationTagRegistry.REPORT_TASK);
        }
        return PreferenceActivationContext.forFinalResponse(tags);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }
}
