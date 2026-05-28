package com.harness.core.exception;

public class ToolExecutionException extends AgentException {
    private final String toolName;

    public ToolExecutionException(String toolName, String message) {
        super("Tool [" + toolName + "]: " + message);
        this.toolName = toolName;
    }

    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super("Tool [" + toolName + "]: " + message, cause);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
