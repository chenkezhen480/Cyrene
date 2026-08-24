package com.harness.core.model;

/**
 * User-visible lifecycle state for one stable tool call.
 */
public enum ToolCallStatus {
    CREATED,
    RUNNING,
    AWAITING_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
