package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Executes tool calls with risk control and timing.
 * Tools requiring user confirmation are flagged via HARNESS_RISK_CONFIRM_TOOLS.
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final List<String> confirmRequiredTools;

    public ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
        this.confirmRequiredTools = EnvConfig.get().getCommaList(EnvKey.RISK_CONFIRM_TOOLS);
    }

    /**
     * Execute a single tool call.
     */
    public ToolResult execute(ToolCall toolCall) {
        String name = toolCall.toolName();
        Tool tool = registry.get(name);

        if (tool == null) {
            log.warn("[L3-Tool] Tool not found: {}", name);
            return ToolResult.fail(toolCall.id(), name, "Tool not found: " + name, 0);
        }

        boolean needsConfirm = confirmRequiredTools.contains(name);
        if (needsConfirm) {
            log.warn("Tool [{}] requires user confirmation (auto-confirm disabled in CLI)", name);
        }

        long start = System.currentTimeMillis();
        String argsStr = toolCall.arguments() != null ? toolCall.arguments().toString() : "null";
        log.debug("[L3-Tool] Executing [{}] with args: {}", name,
                argsStr.length() > 200 ? argsStr.substring(0, 200) + "..." : argsStr);
        try {
            String output = tool.execute(toolCall.arguments());
            long duration = System.currentTimeMillis() - start;
            log.info("[L3-Tool] [{}] executed in {}ms", name, duration);
            log.debug("[L3-Tool] [{}] result: {}", name,
                    output != null && output.length() > 200 ? output.substring(0, 200) + "..." : output);
            return ToolResult.ok(toolCall.id(), name, output, duration);
        } catch (ToolExecutionException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[L3-Tool] [{}] failed in {}ms: {}", name, duration, e.getMessage());
            return ToolResult.fail(toolCall.id(), name, e.getMessage(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[L3-Tool] [{}] unexpected error in {}ms: {}", name, duration, e.getMessage(), e);
            return ToolResult.fail(toolCall.id(), name, "Unexpected error: " + e.getMessage(), duration);
        }
    }

    /**
     * Check if a tool requires user confirmation.
     */
    public boolean requiresConfirmation(String toolName) {
        return confirmRequiredTools.contains(toolName);
    }
}
