package com.harness.tool;

import com.harness.core.model.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A filtered view of ToolRegistry that excludes specific tools.
 * Used to prevent sub-agents from accessing spawn/await/get/cancel_subagent tools.
 */
public class FilteredToolRegistry extends ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(FilteredToolRegistry.class);

    private final ToolRegistry delegate;
    private final Set<String> excludedTools;

    /**
     * Tools that sub-agents should never see.
     */
    public static final Set<String> SUBAGENT_TOOLS = Set.of(
            "spawn_subagent",
            "await_subagents",
            "get_subagents",
            "cancel_subagents"
    );

    public FilteredToolRegistry(ToolRegistry delegate, Set<String> excludedTools) {
        this.delegate = delegate;
        this.excludedTools = ConcurrentHashMap.newKeySet();
        this.excludedTools.addAll(excludedTools);
    }

    /**
     * Create a task-specific registry that only includes the specified tools.
     * Orchestration tools (spawn/await/get/cancel_subagent) are always excluded.
     *
     * @param delegate parent registry to delegate to
     * @param allowedTools tool names this task is allowed to use
     * @return a filtered registry with only allowed tools
     */
    public static FilteredToolRegistry forTask(ToolRegistry delegate, java.util.List<String> allowedTools) {
        // Build exclusion set: all tools EXCEPT the allowed ones
        java.util.Set<String> allowed = new java.util.HashSet<>(allowedTools);
        // Always exclude orchestration tools regardless of what was requested
        allowed.removeAll(SUBAGENT_TOOLS);

        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (ToolSpec spec : delegate.getAll()) {
            if (!allowed.contains(spec.name())) {
                excluded.add(spec.name());
            }
        }
        // Ensure orchestration tools are always excluded
        excluded.addAll(SUBAGENT_TOOLS);

        return new FilteredToolRegistry(delegate, excluded);
    }

    /**
     * Create a registry with NO tools (all excluded).
     * Used when spawn_subagent doesn't specify a tool whitelist — sub-agent is text-only.
     */
    public static FilteredToolRegistry forNoTools(ToolRegistry delegate) {
        Set<String> allToolNames = ConcurrentHashMap.newKeySet();
        delegate.getAll().forEach(spec -> allToolNames.add(spec.name()));
        return new FilteredToolRegistry(delegate, allToolNames);
    }

    @Override
    public void register(Tool tool) {
        // Sub-agents shouldn't register new tools
        String name = tool.spec().name();
        if (excludedTools.contains(name)) {
            log.debug("[FilteredToolRegistry] Blocked registration of excluded tool: {}", name);
            return;
        }
        delegate.register(tool);
    }

    @Override
    public Tool get(String name) {
        if (excludedTools.contains(name)) {
            return null;
        }
        return delegate.get(name);
    }

    @Override
    public List<ToolSpec> getAll() {
        return delegate.getAll().stream()
                .filter(spec -> !excludedTools.contains(spec.name()))
                .toList();
    }

    @Override
    public boolean contains(String name) {
        if (excludedTools.contains(name)) {
            return false;
        }
        return delegate.contains(name);
    }

    @Override
    public int size() {
        return (int) delegate.getAll().stream()
                .filter(spec -> !excludedTools.contains(spec.name()))
                .count();
    }
}
