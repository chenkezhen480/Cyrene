package com.harness.tool;

import com.harness.core.model.ApiEndpoint;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for all available tools.
 * Tools register themselves here; the ReAct engine queries available tools from here.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** Track names of dynamically loaded HttpApiTool instances for clean reload. */
    private final CopyOnWriteArrayList<String> httpApiToolNames = new CopyOnWriteArrayList<>();

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
     * Load confirmed endpoints from a {@link ProjectApiConfig} into the registry.
     * Only endpoints with {@code confirmed == true} are registered.
     * Thread-safe: can be called while ReAct loops are running.
     */
    public synchronized void loadFromConfig(ProjectApiConfig config) {
        if (config == null || config.endpoints() == null) return;
        int loaded = 0;
        for (ApiEndpoint ep : config.endpoints()) {
            if (!ep.confirmed()) continue;
            if (ep.isHighRisk() && !ep.riskAcknowledged()) {
                log.warn("[ToolRegistry] Skipping high-risk endpoint {} (riskAcknowledged=false)", ep.name());
                continue;
            }
            String toolName = "http_" + ep.name();
            if (!tools.containsKey(toolName)) {
                tools.put(toolName, new HttpApiTool(ep));
                httpApiToolNames.add(toolName);
                loaded++;
            }
        }
        if (loaded > 0) {
            log.info("[ToolRegistry] Loaded {} project API endpoints from config", loaded);
        }
    }

    /**
     * Remove all dynamically loaded HttpApiTool instances.
     * Used before reload to clear stale entries.
     */
    public synchronized void unregisterHttpApi() {
        for (String name : httpApiToolNames) {
            tools.remove(name);
        }
        httpApiToolNames.clear();
        log.debug("[ToolRegistry] Unregistered all HttpApiTool instances");
    }

    /**
     * Hot-reload: unregister existing HttpApiTools and re-register from new config.
     * Thread-safe with respect to concurrent ReAct loop tool lookups.
     */
    public synchronized void hotReload(ProjectApiConfig config) {
        unregisterHttpApi();
        loadFromConfig(config);
        log.info("[ToolRegistry] Hot-reload complete: total tools={}", tools.size());
    }
}
