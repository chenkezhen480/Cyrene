package com.harness.tool;

import com.harness.core.model.ToolSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable tool catalog for one agent run.
 *
 * <p>The same catalog supplies tool definitions to the model and resolves tool
 * calls for execution. Registry changes therefore only affect later runs.</p>
 */
public final class RunToolCatalog implements ToolCatalog {

    private final long version;
    private final Map<String, Tool> tools;
    private final List<ToolSpec> specifications;

    RunToolCatalog(long version, Map<String, Tool> tools) {
        this.version = version;
        LinkedHashMap<String, Tool> orderedTools = new LinkedHashMap<>();
        tools.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> orderedTools.put(entry.getKey(), entry.getValue()));
        this.tools = Map.copyOf(orderedTools);
        this.specifications = orderedTools.values().stream()
                .map(Tool::spec)
                .toList();
    }

    public RunToolCatalog excluding(Collection<String> excludedToolNames) {
        Set<String> excluded = excludedToolNames == null
                ? Set.of()
                : Set.copyOf(excludedToolNames);
        LinkedHashMap<String, Tool> filtered = new LinkedHashMap<>();
        tools.forEach((name, tool) -> {
            if (!excluded.contains(name)) {
                filtered.put(name, tool);
            }
        });
        return new RunToolCatalog(version, filtered);
    }

    public RunToolCatalog allowing(Collection<String> allowedToolNames) {
        Set<String> allowed = allowedToolNames == null
                ? Set.of()
                : Set.copyOf(allowedToolNames);
        LinkedHashMap<String, Tool> filtered = new LinkedHashMap<>();
        tools.forEach((name, tool) -> {
            if (allowed.contains(name)) {
                filtered.put(name, tool);
            }
        });
        return new RunToolCatalog(version, filtered);
    }

    @Override
    public Tool get(String name) {
        return tools.get(name);
    }

    @Override
    public List<ToolSpec> getAll() {
        return specifications;
    }

    @Override
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    @Override
    public int size() {
        return tools.size();
    }

    @Override
    public long version() {
        return version;
    }
}
