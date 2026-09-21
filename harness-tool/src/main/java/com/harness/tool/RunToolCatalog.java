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

    private RunToolCatalog(
            long version,
            Map<String, Tool> tools,
            List<ToolSpec> specifications
    ) {
        this.version = version;
        this.tools = Map.copyOf(tools);
        this.specifications = List.copyOf(specifications);
    }

    /**
     * Return a catalog without the named tools.
     *
     * <p>A name may also address one action of a merged tool ({@code code_workspace.edit}) or its
     * pre-merge spelling ({@code edit}). That is handled here rather than at each call site because
     * every caller — the per-request denylist, the detached-resume re-resolution — must agree, and a
     * missed call site would silently leave a denied action in front of the model.</p>
     */
    public RunToolCatalog excluding(Collection<String> excludedToolNames) {
        Set<String> excluded = excludedToolNames == null
                ? Set.of()
                : Set.copyOf(excludedToolNames);
        if (excluded.isEmpty()) {
            return this;
        }
        LinkedHashMap<String, Tool> filtered = new LinkedHashMap<>();
        Map<String, ToolSpec> narrowedSpecs = new LinkedHashMap<>();
        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            String name = entry.getKey();
            Tool tool = entry.getValue();
            if (excluded.contains(name)) {
                continue;
            }
            if (tool instanceof ToolGroup group) {
                ToolGroup narrowed = group.denying(excluded);
                if (!narrowed.hasActions()) {
                    continue;
                }
                if (narrowed != group) {
                    narrowedSpecs.put(name, narrowed.spec());
                }
                filtered.put(name, narrowed);
                continue;
            }
            filtered.put(name, tool);
        }
        // No name matched anything: same tools, so the caller's snapshot is still valid. The
        // narrowed check comes first because narrowing removes nothing — a denylist naming only
        // `code_workspace.edit` leaves the tool count untouched while still restricting it.
        if (narrowedSpecs.isEmpty() && filtered.size() == tools.size()) {
            return this;
        }
        return new RunToolCatalog(version, filtered, narrowed(filtered, narrowedSpecs));
    }

    /**
     * Return a catalog containing only the named tools. An allowlist may address a merged tool's
     * actions instead of the whole tool, so a sub-agent granted only {@code code_workspace.read}
     * never receives the write actions.
     */
    public RunToolCatalog allowing(Collection<String> allowedToolNames) {
        Set<String> allowed = allowedToolNames == null
                ? Set.of()
                : Set.copyOf(allowedToolNames);
        if (allowed.containsAll(tools.keySet())) {
            return this;
        }
        LinkedHashMap<String, Tool> filtered = new LinkedHashMap<>();
        Map<String, ToolSpec> narrowedSpecs = new LinkedHashMap<>();
        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            String name = entry.getKey();
            Tool tool = entry.getValue();
            if (tool instanceof ToolGroup group) {
                ToolGroup narrowed = group.allowing(allowed);
                if (!narrowed.hasActions()) {
                    continue;
                }
                if (narrowed != group) {
                    narrowedSpecs.put(name, narrowed.spec());
                }
                filtered.put(name, narrowed);
                continue;
            }
            if (allowed.contains(name)) {
                filtered.put(name, tool);
            }
        }
        return new RunToolCatalog(version, filtered, narrowed(filtered, narrowedSpecs));
    }

    /**
     * The surviving specifications, reusing the cached instances. Only a narrowed merged tool has to
     * be re-derived; re-specifying the whole catalog on every filtered run would undo the point of
     * caching them.
     */
    private List<ToolSpec> narrowed(Map<String, Tool> filtered, Map<String, ToolSpec> narrowedSpecs) {
        return specifications.stream()
                .filter(specification -> filtered.containsKey(specification.name()))
                .map(specification -> narrowedSpecs.getOrDefault(specification.name(), specification))
                .toList();
    }

    /**
     * Return a request-scoped catalog with one tool added or replaced.
     * Existing snapshots remain unchanged.
     */
    public RunToolCatalog replacing(Tool replacement) {
        if (replacement == null || replacement.spec() == null
                || replacement.spec().name() == null
                || replacement.spec().name().isBlank()) {
            throw new IllegalArgumentException("Replacement tool name must not be blank");
        }
        LinkedHashMap<String, Tool> updated = new LinkedHashMap<>(tools);
        updated.put(replacement.spec().name(), replacement);
        return new RunToolCatalog(version, updated);
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
        if (tools.containsKey(name)) {
            return true;
        }
        // A merged tool answers to its actions too, so a sub-agent contract may require
        // `code_workspace.read` — or its pre-merge spelling `read` — and still mean something.
        return tools.values().stream().anyMatch(tool -> tool instanceof ToolGroup group
                && group.supports(name));
    }

    /** Canonical permission/contract name for a registered tool or one of its actions. */
    public String canonicalName(String name) {
        if (tools.containsKey(name)) return name;
        for (Tool tool : tools.values()) {
            if (tool instanceof ToolGroup group && group.supports(name)) {
                return group.canonicalName(name);
            }
        }
        return name;
    }

    public String permissionName(String name) {
        if (tools.containsKey(name)) return name;
        for (Tool tool : tools.values()) {
            if (tool instanceof ToolGroup group && group.supports(name)) {
                return group.permissionName(name);
            }
        }
        return name;
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
