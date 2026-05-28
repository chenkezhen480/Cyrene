package com.harness.tool;

import com.harness.core.model.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all available tools.
 * Tools register themselves here; the ReAct engine queries available tools from here.
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * Register a tool. Overwrites if name already exists.
     */
    public void register(Tool tool) {
        tools.put(tool.spec().name(), tool);
    }

    /**
     * Get a tool by name.
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * Get all registered tool specs (for LLM tool definitions).
     */
    public List<ToolSpec> getAll() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /**
     * Check if a tool is registered.
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public int size() {
        return tools.size();
    }
}
