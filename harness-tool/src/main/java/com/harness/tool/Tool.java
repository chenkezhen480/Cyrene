package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.model.ToolSpec;

/**
 * Interface that all tools must implement.
 * Tools are registered in the ToolRegistry and invoked by the ReAct engine.
 */
public interface Tool {

    /**
     * Tool specification (name, description, parameters schema).
     */
    ToolSpec spec();

    /**
     * Execute the tool with given arguments.
     *
     * @param arguments JSON arguments
     * @return string result
     * @throws com.harness.core.exception.ToolExecutionException on failure
     */
    String execute(JsonNode arguments);
}
