package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.harness.core.model.ToolCapability;
import com.harness.tool.Tool;
import com.harness.tool.ToolGroup;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Local code capability, assembled at the workspace lifecycle boundary. */
public final class CodeWorkspaceTool extends ToolGroup {
    public static final String TOOL_NAME = "code_workspace";
    public static final List<String> ACTIONS = List.of(
            ReadTool.TOOL_NAME, GlobTool.TOOL_NAME, GrepTool.TOOL_NAME, TreeTool.TOOL_NAME,
            EditTool.TOOL_NAME, WriteTool.TOOL_NAME, ApplyPatchTool.TOOL_NAME, "help");
    private static final Set<String> WRITE_ACTIONS =
            Set.of(EditTool.TOOL_NAME, WriteTool.TOOL_NAME, ApplyPatchTool.TOOL_NAME);

    private CodeWorkspaceTool(Map<String, Tool> actions, Set<String> confirmActions) {
        super(TOOL_NAME, "Local code workspace", actions, confirmActions);
    }

    public static CodeWorkspaceTool of(
            FileSystemAccessPolicy policy,
            FileSystemWorkspace workspace,
            Collection<String> confirmActions
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(workspace, "workspace");
        Map<String, Tool> actions = new LinkedHashMap<>();
        actions.put(ReadTool.TOOL_NAME, new ReadTool(policy, workspace));
        actions.put(GlobTool.TOOL_NAME, new GlobTool(policy, workspace));
        actions.put(GrepTool.TOOL_NAME, new GrepTool(policy, workspace));
        actions.put(TreeTool.TOOL_NAME, new TreeTool(policy, workspace));
        if (workspace.writable()) {
            actions.put(EditTool.TOOL_NAME, new EditTool(policy, workspace));
            actions.put(WriteTool.TOOL_NAME, new WriteTool(policy, workspace));
            actions.put(ApplyPatchTool.TOOL_NAME, new ApplyPatchTool(policy, workspace));
        }
        return new CodeWorkspaceTool(actions, Set.copyOf(confirmActions));
    }

    @Override
    protected CodeWorkspaceTool withActions(Map<String, Tool> remaining) {
        return new CodeWorkspaceTool(remaining, confirmActions);
    }

    @Override
    public CodeWorkspaceTool denying(Collection<String> denied) {
        return (CodeWorkspaceTool) super.denying(denied);
    }

    @Override
    public CodeWorkspaceTool allowing(Collection<String> allowed) {
        return (CodeWorkspaceTool) super.allowing(allowed);
    }

    // Existing code-workspace conversations used flat action parameters.
    @Override
    protected JsonNode actionInput(JsonNode arguments) {
        JsonNode input = arguments == null ? null : arguments.get("input");
        return input == null || input.isNull() ? arguments : super.actionInput(arguments);
    }

    @Override
    protected ToolCapability capability() {
        return writable() ? ToolCapability.MUTATION : ToolCapability.RETRIEVAL;
    }

    public boolean writable() {
        return actions.keySet().stream().anyMatch(WRITE_ACTIONS::contains);
    }

    @Override
    public String permissionName(String candidate) {
        // Previous releases treated persisted bare code-tool denials as a whole-workspace denial.
        return ACTIONS.contains(candidate) ? TOOL_NAME : super.permissionName(candidate);
    }

    @Override
    protected String description() {
        StringBuilder text = new StringBuilder("Browse, search and read code in the local workspace. ")
                .append("Use help to load one action's parameters. A natural flow is tree/glob, grep, ")
                .append("then read a precise range");
        if (actions.containsKey(ApplyPatchTool.TOOL_NAME)) {
            text.append(" and patch");
        }
        text.append(", followed by a targeted shell check.");
        if (actions.containsKey(ApplyPatchTool.TOOL_NAME)) {
            text.append(" patch is the default modification action.");
        }
        if (actions.containsKey(EditTool.TOOL_NAME)) {
            text.append(" edit handles one exact replacement.");
        }
        if (actions.containsKey(WriteTool.TOOL_NAME)) {
            text.append(" write creates one new file.");
        }
        if (writable()) {
            text.append(" Writes stay inside writable roots.");
        }
        return text.toString();
    }

    @Override
    public String confirmationSummary(JsonNode arguments) {
        String action = arguments == null ? null : text(arguments.get("action"));
        JsonNode input = actionInput(arguments);
        String target = input == null
                ? null
                : firstNonNull(
                        text(input.get("file_path")),
                        text(input.get("path")),
                        text(input.get("pattern")));
        return target == null
                ? TOOL_NAME + " " + (action == null ? "" : action)
                : TOOL_NAME + " " + (action == null ? "" : action) + ": " + target;
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }

    private static String firstNonNull(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

}
