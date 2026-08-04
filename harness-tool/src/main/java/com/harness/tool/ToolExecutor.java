package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.confirmation.ConfirmationDecision;
import com.harness.tool.confirmation.ConfirmationExecutionContext;
import com.harness.tool.confirmation.ConfirmationManager;
import com.harness.tool.confirmation.ConfirmationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Executes tool calls with risk control and timing.
 * Confirmation is required when declared by {@link com.harness.core.model.ToolSpec}
 * or when the tool name is listed in HARNESS_RISK_CONFIRM_TOOLS.
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ConfirmationManager confirmationManager;
    private final List<String> confirmRequiredTools;

    public ToolExecutor(ConfirmationManager confirmationManager) {
        this.confirmationManager = Objects.requireNonNull(confirmationManager, "confirmationManager");
        this.confirmRequiredTools = EnvConfig.get().getCommaList(EnvKey.RISK_CONFIRM_TOOLS);
    }

    /**
     * Execute a tool instance already resolved through the current run's immutable catalog.
     * The executor deliberately has no global name lookup path.
     */
    public ToolResult executeAuthorized(
            ToolCall toolCall,
            Tool authorizedTool,
            ConfirmationExecutionContext confirmationContext
    ) {
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(authorizedTool, "authorizedTool");
        String name = toolCall.toolName();
        if (!name.equals(authorizedTool.spec().name())) {
            log.error("[L3-Tool] Authorized tool name mismatch: call={}, tool={}",
                    name, authorizedTool.spec().name());
            return ToolResult.fail(toolCall.id(), name, "Authorized tool name mismatch", 0);
        }

        Tool tool = authorizedTool;
        boolean confirmationRequired = requiresConfirmation(tool, toolCall.arguments());
        if (confirmationRequired) {
            if (confirmationContext != null) {
                return executeAfterConfirmation(
                        toolCall,
                        tool,
                        confirmationContext,
                        confirmationSummary(tool, toolCall.arguments()));
            }
            String message = "Tool requires explicit confirmation before execution: " + name;
            log.warn("[L3-Tool] Blocked [{}]: confirmation required", name);
            return ToolResult.confirmationRequired(toolCall.id(), name, message);
        }

        notifyExecutionStart(confirmationContext, toolCall);
        return executeTool(toolCall, tool);
    }

    private ToolResult executeAfterConfirmation(ToolCall toolCall, Tool tool,
                                                ConfirmationExecutionContext context,
                                                String confirmationSummary) {
        ConfirmationRequest request = confirmationManager.create(
                context.userId(),
                context.sessionId(),
                toolCall,
                confirmationSummary);
        try {
            log.info("[L3-Tool] Waiting for confirmation [{}]: tool={}",
                    request.requestId(), toolCall.toolName());
            context.onConfirmationRequired().accept(request);
            ConfirmationDecision decision = confirmationManager.awaitDecision(
                    request.requestId(), context.cancellationToken());
            context.onConfirmationResolved().accept(request, decision);

            return switch (decision) {
                case APPROVED -> executeApproved(request, toolCall, tool, context);
                case REJECTED -> ToolResult.confirmationRejected(
                        toolCall.id(), toolCall.toolName(), "User rejected the tool execution");
                case EXPIRED -> ToolResult.confirmationExpired(
                        toolCall.id(), toolCall.toolName(), "Tool confirmation request expired");
                case CANCELLED -> ToolResult.confirmationCancelled(
                        toolCall.id(), toolCall.toolName(), "Tool confirmation was cancelled");
            };
        } finally {
            confirmationManager.release(request.requestId());
        }
    }

    private ToolResult executeApproved(ConfirmationRequest request, ToolCall toolCall, Tool tool,
                                       ConfirmationExecutionContext context) {
        if (!confirmationManager.consumeApproved(request.requestId(), toolCall)) {
            log.error("[L3-Tool] Approval validation failed [{}]: tool={}",
                    request.requestId(), toolCall.toolName());
            return ToolResult.fail(
                    toolCall.id(), toolCall.toolName(), "Approval validation failed", 0);
        }
        log.info("[L3-Tool] Confirmation approved [{}]: tool={}",
                request.requestId(), toolCall.toolName());
        notifyExecutionStart(context, toolCall);
        return executeTool(toolCall, tool);
    }

    private void notifyExecutionStart(ConfirmationExecutionContext context, ToolCall toolCall) {
        if (context != null) {
            context.onExecutionStart().accept(toolCall.toolName(), toolCall.arguments());
        }
    }

    private ToolResult executeTool(ToolCall toolCall, Tool tool) {
        String name = toolCall.toolName();
        long start = System.currentTimeMillis();
        String argsStr = toolCall.arguments() != null ? toolCall.arguments().toString() : "null";
        log.debug("[L3-Tool] Executing [{}] with args: {}", name,
                argsStr.length() > 200 ? argsStr.substring(0, 200) + "..." : argsStr);
        ToolResult.clearCurrentStatus();
        try {
            String output = tool.execute(toolCall.arguments());
            long duration = System.currentTimeMillis() - start;
            // Consume explicit status set by tool via ThreadLocal (null if tool didn't set one)
            ToolResult.ResultStatus status = ToolResult.consumeCurrentStatus();
            log.debug("[L3-Tool] [{}] executed in {}ms", name, duration);
            log.debug("[L3-Tool] [{}] result: {}", name,
                    output != null && output.length() > 200 ? output.substring(0, 200) + "..." : output);
            return ToolResult.ok(toolCall.id(), name, output, duration, status);
        } catch (ToolExecutionException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[L3-Tool] [{}] failed in {}ms: {}", name, duration, e.getMessage());
            return ToolResult.fail(toolCall.id(), name, e.getMessage(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[L3-Tool] [{}] unexpected error in {}ms: {}", name, duration, e.getMessage(), e);
            return ToolResult.fail(toolCall.id(), name, "Unexpected error: " + e.getMessage(), duration);
        } finally {
            ToolResult.clearCurrentStatus();
        }
    }

    private boolean requiresConfirmation(Tool tool, JsonNode arguments) {
        return tool.spec().requiresConfirmation()
                || confirmRequiredTools.contains(tool.spec().name())
                || (tool instanceof ArgumentAwareConfirmationTool argumentAware
                        && argumentAware.requiresConfirmation(arguments));
    }

    private String confirmationSummary(Tool tool, JsonNode arguments) {
        if (tool instanceof ArgumentAwareConfirmationTool argumentAware) {
            return argumentAware.confirmationSummary(arguments);
        }
        return tool.spec().description();
    }
}
