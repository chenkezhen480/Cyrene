package com.harness.react;

import com.harness.tool.ToolCatalog;
import com.harness.tool.ToolExecutor;

/** Creates request-scoped ReAct loops from an immutable tool catalog. */
public interface ReActLoopFactory {
    ReActLoop create(ToolCatalog toolCatalog, ToolExecutor toolExecutor);
    ReActLoop create(ToolCatalog toolCatalog, ToolExecutor toolExecutor, int maxIterations);
}
