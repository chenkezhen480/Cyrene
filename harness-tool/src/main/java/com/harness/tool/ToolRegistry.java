package com.harness.tool;

import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central registry for all available tools.
 * Tools register themselves here; the ReAct engine queries available tools from here.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** In-memory project API config, shared by the three discovery meta-tools. */
    private final AtomicReference<ProjectApiConfig> configRef = new AtomicReference<>();

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

    // ==================== Project API Discovery support ====================

    /**
     * Load project API config and register the three discovery meta-tools.
     * Stores config in memory for the tools to query.
     * Thread-safe: can be called while ReAct loops are running.
     */
    public synchronized void loadFromConfig(ProjectApiConfig config) {
        if (config == null) return;

        // Store config in memory (tools query via configRef)
        configRef.set(config);

        // Register the three meta-tools (idempotent — overwrite if exists)
        tools.put("list_api_endpoints", new ListApiEndpointsTool(configRef::get));
        tools.put("get_api_endpoint_detail", new GetApiEndpointDetailTool(configRef::get));
        tools.put("call_discovered_api", new CallDiscoveredApiTool(configRef::get));

        log.info("[ToolRegistry] Loaded project API config: {} endpoints, 3 meta-tools registered",
                config.endpoints() != null ? config.endpoints().size() : 0);
    }

    /**
     * Get the current project API config (for external queries).
     */
    public ProjectApiConfig getProjectApiConfig() {
        return configRef.get();
    }

    /**
     * Hot-reload: update config in memory. Meta-tools automatically see new data via AtomicReference.
     * Thread-safe with respect to concurrent ReAct loop tool lookups.
     */
    public synchronized void hotReload(ProjectApiConfig config) {
        configRef.set(config);
        log.info("[ToolRegistry] Hot-reload complete: {} endpoints, total tools={}",
                config.endpoints() != null ? config.endpoints().size() : 0, tools.size());
    }
}
