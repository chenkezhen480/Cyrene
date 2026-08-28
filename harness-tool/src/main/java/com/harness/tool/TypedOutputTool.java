package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.model.ToolOutput;

/**
 * Tool contract for implementations that return the unified typed output model.
 * Text, artifacts, and structured JSON are independent optional components.
 */
public interface TypedOutputTool extends Tool {

    @Override
    ToolOutput executeOutput(JsonNode arguments);

    @Override
    default String execute(JsonNode arguments) {
        return executeOutput(arguments).modelContent();
    }
}
