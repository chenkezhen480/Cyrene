package com.harness.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ProjectApiConfig;
import com.harness.core.model.ToolSpec;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.tool.discovery.UpdateProjectApiTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable application-level registry. Agent runs consume immutable snapshots
 * created by {@link #snapshot()}.
 */
public class ToolRegistry implements ToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final AtomicReference<RegistryState> state =
            new AtomicReference<>(new RegistryState(0, Map.of()));
    private final AtomicReference<ProjectApiConfig> configRef = new AtomicReference<>();

    /**
     * Register a new tool.
     *
     * @throws IllegalArgumentException when a tool with the same name already exists
     */
    public synchronized void register(Tool tool) {
        String name = requireToolName(tool);
        RegistryState current = state.get();
        if (current.tools().containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
        replaceStateEntry(current, name, tool);
    }

    /**
     * Explicitly replace a tool implementation. Existing run snapshots retain
     * the previous implementation.
     */
    public synchronized void replace(Tool tool) {
        String name = requireToolName(tool);
        replaceStateEntry(state.get(), name, tool);
    }

    /** Atomically publish a set of tool replacements and removals for later runs. */
    public synchronized void applyChanges(
            Map<String, Tool> replacements,
            Set<String> removals
    ) {
        Map<String, Tool> safeReplacements = replacements == null
                ? Map.of()
                : Map.copyOf(replacements);
        Set<String> safeRemovals = removals == null ? Set.of() : Set.copyOf(removals);
        safeReplacements.forEach((name, tool) -> {
            String actualName = requireToolName(tool);
            if (!name.equals(actualName)) {
                throw new IllegalArgumentException(
                        "Tool replacement key does not match specification: " + name);
            }
        });
        Map<String, Tool> updated = new HashMap<>(state.get().tools());
        safeRemovals.forEach(updated::remove);
        updated.putAll(safeReplacements);
        RegistryState current = state.get();
        state.set(new RegistryState(current.version() + 1, Map.copyOf(updated)));
    }

    @Override
    public Tool get(String name) {
        return state.get().tools().get(name);
    }

    @Override
    public List<ToolSpec> getAll() {
        return state.get().tools().values().stream()
                .map(Tool::spec)
                .sorted(Comparator.comparing(ToolSpec::name))
                .toList();
    }

    @Override
    public boolean contains(String name) {
        return state.get().tools().containsKey(name);
    }

    @Override
    public int size() {
        return state.get().tools().size();
    }

    @Override
    public long version() {
        return state.get().version();
    }

    /**
     * Freeze tool definitions and implementations for one agent run.
     */
    public RunToolCatalog snapshot() {
        RegistryState current = state.get();
        return new RunToolCatalog(current.version(), current.tools());
    }

    /**
     * Atomically replace the project API meta-tools. Tools in an existing run
     * continue using the configuration captured by that run.
     */
    public synchronized void loadFromConfig(ProjectApiConfig config) {
        if (config == null) {
            return;
        }

        RegistryState current = state.get();
        Map<String, Tool> updatedTools = new HashMap<>(current.tools());
        updatedTools.put("list_api_endpoints", new ListApiEndpointsTool(() -> config));
        updatedTools.put("get_api_endpoint_detail", new GetApiEndpointDetailTool(() -> config));
        updatedTools.put("call_discovered_api", new CallDiscoveredApiTool(() -> config));
        updatedTools.put("update_project_api", new UpdateProjectApiTool(this));

        configRef.set(config);
        state.set(new RegistryState(current.version() + 1, Map.copyOf(updatedTools)));

        int total = config.endpoints() != null ? config.endpoints().size() : 0;
        int callable = ProjectApiPolicy.callableEndpoints(config).size();
        log.info("[ToolRegistry] Loaded project API config: {} total, {} callable, 4 meta-tools registered",
                total, callable);
    }

    /**
     * Persist a project API configuration before publishing it to later runs.
     */
    public synchronized boolean updateProjectApiConfig(ProjectApiConfig newConfig) {
        if (newConfig == null) {
            return false;
        }
        Path tempPath = null;
        try {
            String configPath = EnvConfig.get().getString(
                    EnvKey.PROJECT_APIS_CONFIG_FILE, "./project-apis.json");
            Path targetPath = Path.of(configPath).toAbsolutePath().normalize();
            Path parentPath = targetPath.getParent();
            if (parentPath == null || !Files.isDirectory(parentPath)) {
                throw new IllegalStateException("Config directory does not exist: " + parentPath);
            }

            ObjectMapper mapper = new ObjectMapper();
            tempPath = Files.createTempFile(
                    parentPath, targetPath.getFileName().toString() + ".", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), newConfig);
            Files.move(tempPath, targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            loadFromConfig(newConfig);
            log.info("[ToolRegistry] Config synced to disk: {} endpoints",
                    newConfig.endpoints().size());
            return true;
        } catch (Exception e) {
            log.error("[ToolRegistry] Failed to write config to disk: {}", e.getMessage());
            return false;
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception e) {
                    log.warn("[ToolRegistry] Failed to clean temporary config file {}: {}",
                            tempPath, e.getMessage());
                }
            }
        }
    }

    public ProjectApiConfig getProjectApiConfig() {
        return configRef.get();
    }

    private static String requireToolName(Tool tool) {
        ToolSpec specification = tool == null ? null : tool.spec();
        if (specification == null
                || specification.name() == null || specification.name().isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be blank");
        }
        return specification.name();
    }

    private void replaceStateEntry(RegistryState current, String name, Tool tool) {
        Map<String, Tool> updatedTools = new HashMap<>(current.tools());
        updatedTools.put(name, tool);
        state.set(new RegistryState(current.version() + 1, Map.copyOf(updatedTools)));
    }

    private record RegistryState(long version, Map<String, Tool> tools) {
    }
}
