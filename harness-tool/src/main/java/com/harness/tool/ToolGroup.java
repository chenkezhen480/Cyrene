package com.harness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable action dispatcher. Composition roots inject only enabled tools. */
public class ToolGroup implements ArgumentAwareConfirmationTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String name;
    private final String description;
    protected final Map<String, Tool> actions;
    protected final Set<String> confirmActions;

    public ToolGroup(String name, String description, Map<String, Tool> actions,
                     Collection<String> confirmActions) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool group name must not be blank");
        }
        this.name = name;
        this.description = Objects.requireNonNull(description, "description");
        LinkedHashMap<String, Tool> copy = new LinkedHashMap<>();
        actions.forEach((action, tool) -> {
            if (action == null || action.isBlank() || action.contains(".") || "help".equals(action)) {
                throw new IllegalArgumentException("Invalid tool group action: " + action);
            }
            copy.put(action, Objects.requireNonNull(tool, "tool"));
        });
        this.actions = Collections.unmodifiableMap(copy);
        this.confirmActions = Set.copyOf(confirmActions);
    }

    protected ToolGroup withActions(Map<String, Tool> remaining) {
        return new ToolGroup(name, description, remaining, confirmActions);
    }

    public ToolGroup denying(Collection<String> denied) {
        if (denied == null || denied.isEmpty()) return this;
        Map<String, Tool> remaining = new LinkedHashMap<>(actions);
        remaining.entrySet().removeIf(entry -> denied.contains(name)
                || matches(denied, entry.getKey(), entry.getValue()));
        return remaining.size() == actions.size() ? this : withActions(remaining);
    }

    public ToolGroup allowing(Collection<String> allowed) {
        if (allowed != null && allowed.contains(name)) return this;
        Map<String, Tool> remaining = new LinkedHashMap<>(actions);
        remaining.entrySet().removeIf(entry -> allowed == null
                || !matches(allowed, entry.getKey(), entry.getValue()));
        return remaining.size() == actions.size() ? this : withActions(remaining);
    }

    private boolean matches(Collection<String> names, String action, Tool tool) {
        return names.contains(name + "." + action) || names.contains(tool.spec().name());
    }

    /** Resolve legacy delegate names without allowing bare action names to cross group boundaries. */
    public String canonicalName(String candidate) {
        for (Map.Entry<String, Tool> entry : actions.entrySet()) {
            if (matches(Set.of(candidate), entry.getKey(), entry.getValue())) {
                return name + "." + entry.getKey();
            }
        }
        return candidate;
    }

    public boolean supports(String candidate) {
        if (candidate == null) return false;
        if ((name + ".help").equals(candidate)) return hasActions();
        return actions.entrySet().stream()
                .anyMatch(entry -> matches(Set.of(candidate), entry.getKey(), entry.getValue()));
    }

    /** Stored permissions may need a stricter legacy migration than task allowlists. */
    public String permissionName(String candidate) {
        return canonicalName(candidate);
    }

    public boolean hasActions() { return !actions.isEmpty(); }

    public Set<String> availableActions() {
        Set<String> offered = new LinkedHashSet<>(actions.keySet());
        offered.add("help");
        return Collections.unmodifiableSet(offered);
    }

    /** Per-action specs for permission management; help never grants an execution capability. */
    public List<ToolSpec> actionSpecs() {
        return actions.entrySet().stream().map(entry -> {
            ToolSpec spec = entry.getValue().spec();
            return new ToolSpec(name + "." + entry.getKey(), spec.description(),
                    spec.parameters(), spec.capability());
        }).toList();
    }

    protected String description() { return description; }

    protected ToolCapability capability() {
        Set<ToolCapability> capabilities = new java.util.HashSet<>();
        actions.values().forEach(tool -> capabilities.add(tool.spec().capability()));
        return capabilities.size() == 1 ? capabilities.iterator().next() : ToolCapability.UNKNOWN;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ObjectNode action = properties.putObject("action").put("type", "string")
                .put("description", "Available: " + String.join(", ", availableActions()));
        availableActions().forEach(action.putArray("enum")::add);
        properties.putObject("input").put("type", "object").put("additionalProperties", true)
                .put("description", "Parameters for the action. Use help with input.action for its schema.");
        schema.putArray("required").add("action").add("input");
        return new ToolSpec(name, description(), schema, capability());
    }

    /** The selected delegate, also used to route cancellation without cancelling sibling actions. */
    public Tool delegate(JsonNode arguments) {
        return arguments == null ? null : actions.get(arguments.path("action").asText());
    }

    protected JsonNode actionInput(JsonNode arguments) {
        JsonNode input = arguments == null ? null : arguments.get("input");
        if (input == null || !input.isObject()) {
            throw new ToolExecutionException(name, "input must be a JSON object");
        }
        return input;
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()
                || !arguments.path("action").isTextual()) {
            throw new ToolExecutionException(name, "Missing required parameter: action");
        }
        String action = arguments.path("action").asText();
        JsonNode input = actionInput(arguments);
        if ("help".equals(action)) return help(input);
        Tool tool = delegate(arguments);
        if (tool == null) throw unavailable(action);
        return tool.executeOutcome(input);
    }

    private ToolExecutionOutcome help(JsonNode input) {
        JsonNode requested = input.get("action");
        ObjectNode json = MAPPER.createObjectNode();
        if (requested == null || requested.isNull()) {
            actions.keySet().forEach(json.putArray("actions")::add);
            return ToolExecutionOutcome.available(new ToolOutput(
                    "Available actions: " + String.join(", ", actions.keySet())
                            + ". Call help with input.action for its schema.", List.of(), json));
        }
        Tool tool = requested.isTextual() ? actions.get(requested.asText()) : null;
        if (tool == null) throw unavailable(requested.asText());
        ToolSpec spec = tool.spec();
        json.put("action", requested.asText());
        json.put("description", spec.description());
        json.set("inputSchema", spec.parameters());
        return ToolExecutionOutcome.available(new ToolOutput(
                requested.asText() + ": " + spec.description(), List.of(), json));
    }

    private ToolExecutionException unavailable(String action) {
        return new ToolExecutionException(name, "unknown or unavailable action '" + action
                + "'; available: " + String.join(", ", actions.keySet()));
    }

    @Override
    public boolean requiresConfirmation(JsonNode arguments) {
        if (confirmActions.contains(name)) return true;
        Tool tool = delegate(arguments);
        if (tool == null) return false;
        String action = arguments.path("action").asText();
        return matches(confirmActions, action, tool) || tool.spec().requiresConfirmation()
                || (tool instanceof ArgumentAwareConfirmationTool aware
                && aware.requiresConfirmation(actionInput(arguments)));
    }

    @Override
    public String confirmationSummary(JsonNode arguments) {
        Tool tool = delegate(arguments);
        String action = arguments == null ? "" : arguments.path("action").asText();
        return name + "." + action + ": "
                + (tool instanceof ArgumentAwareConfirmationTool aware
                ? aware.confirmationSummary(actionInput(arguments)) : description());
    }
}
