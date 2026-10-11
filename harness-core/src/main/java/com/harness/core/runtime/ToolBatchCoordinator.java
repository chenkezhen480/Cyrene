package com.harness.core.runtime;

import com.harness.core.model.CancellationToken;
import com.harness.core.model.ToolCall;
import com.harness.core.model.ToolResult;

import java.util.List;

/** Resolves a submitted batch before its results enter the conversation. */
@FunctionalInterface
public interface ToolBatchCoordinator {
    List<ToolResult> coordinate(List<ToolCall> calls, List<ToolResult> results,
                               CancellationToken cancellationToken);

    static ToolBatchCoordinator passthrough() {
        return (calls, results, token) -> results;
    }
}
