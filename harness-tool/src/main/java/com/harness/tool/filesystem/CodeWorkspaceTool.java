package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolSpec;
import com.harness.tool.ArgumentAwareConfirmationTool;
import com.harness.tool.Tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single public entry point for local code browsing and editing, dispatching on {@code action}.
 *
 * <p>Six near-identical tools ({@code read}, {@code glob}, {@code grep}, {@code tree}, {@code edit},
 * {@code write}) cost six JSON Schemas in every request and six rows in the permission console, for
 * one capability. They stay as implementations — the project-discovery scan registers them directly,
 * and their unit tests construct them directly — but only this tool is ever published to a model.</p>
 *
 * <p><b>Why the schema is flat rather than a discriminated union.</b> {@code oneOf} is not merely
 * expensive here, it is unsupported: {@code LangChainJsonSchemaMapper} infers a node's type from
 * {@code type}/{@code properties}/{@code items} and throws for a node that declares {@code oneOf}
 * and no {@code type}, which surfaces as "Invalid parameter schema for tool". {@code enum} is
 * supported and already used elsewhere, so the action vocabulary lives in an enum and the
 * per-action field mapping lives in prose.</p>
 *
 * <p><b>Denied actions are absent, not rejected.</b> {@link #denying} returns a variant whose
 * {@code action} enum no longer lists them, so a tenant-restricted run is never offered an action it
 * may not take. The enum is what the model sees; the lookup in {@link #executeOutcome} is what
 * actually stops a call, because providers do not enforce {@code enum}, {@code required}, or
 * {@code additionalProperties} on our behalf. Both layers are load-bearing.</p>
 */
public final class CodeWorkspaceTool implements ArgumentAwareConfirmationTool {

    public static final String TOOL_NAME = "code_workspace";

    /** The action vocabulary, in schema order. Kept in the sub-tools' spelling — one source. */
    public static final List<String> ACTIONS = List.of(
            ReadTool.TOOL_NAME,
            GlobTool.TOOL_NAME,
            GrepTool.TOOL_NAME,
            TreeTool.TOOL_NAME,
            EditTool.TOOL_NAME,
            WriteTool.TOOL_NAME);

    /** The actions that change files. They are what separates the two capability tags. */
    private static final Set<String> WRITE_ACTIONS =
            Set.of(EditTool.TOOL_NAME, WriteTool.TOOL_NAME);

    private final Map<String, Tool> actions;
    private final Set<String> confirmActions;

    private CodeWorkspaceTool(Map<String, Tool> actions, Set<String> confirmActions) {
        // Not Map.copyOf: it does not promise iteration order, and the order here is the order the
        // action enum and the console's permission rows are rendered in.
        this.actions = Collections.unmodifiableMap(new LinkedHashMap<>(actions));
        this.confirmActions = Set.copyOf(confirmActions);
    }

    /**
     * Build the tool for one workspace. {@code edit} and {@code write} are added only where the
     * scope is writable — the same guard the six were registered under, kept because a read-only
     * scope must not even advertise a write action.
     *
     * @param confirmActions {@code HARNESS_RISK_CONFIRM_TOOLS}, matched against this tool's own
     *                       action names and against the legacy ones, so an operator who required
     *                       confirmation on {@code edit} keeps it after the merge
     */
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
        }
        return new CodeWorkspaceTool(actions, Set.copyOf(confirmActions));
    }

    /**
     * The old per-tool name a merged action replaced, rewritten to its {@code code_workspace.<action>}
     * spelling. Anything already spelled that way, and anything unrelated, passes through untouched.
     *
     * <p>Without this, stored state keyed on the old names goes silently inert: a tenant that had
     * disabled {@code edit} would regain write access, and an operator who required confirmation on
     * {@code edit,write} would stop being asked.</p>
     */
    public static String modernize(String toolName) {
        return ACTIONS.contains(toolName) ? TOOL_NAME + "." + toolName : toolName;
    }

    /**
     * The tool that now owns a pre-merge action name, for the permission store — which keys on one
     * name per tool and cannot express an action. Anything that was not one of those names comes
     * back untouched.
     *
     * <p>Distinct from {@link #modernize} on purpose. The store is managed one row per tool, so a
     * stored {@code edit} has to resolve to the whole {@code code_workspace}; a narrower answer
     * would leave the admin page reporting the tool as unrestricted while a request for it is
     * actually limited. The runtime keeps the finer spelling because an allowlist may legitimately
     * name one action.</p>
     */
    public static String ownerOf(String toolName) {
        return ACTIONS.contains(toolName) ? TOOL_NAME : toolName;
    }

    /**
     * A variant without the denied actions, or {@code this} when none of {@code denied} names one —
     * so a run with nothing denied pays no rebuild of the catalog.
     *
     * @see #hasActions()
     */
    public CodeWorkspaceTool denying(Collection<String> denied) {
        if (denied == null || denied.isEmpty()) {
            return this;
        }
        Set<String> removed = new LinkedHashSet<>();
        for (String name : denied) {
            String modern = modernize(name);
            if (modern != null && modern.startsWith(TOOL_NAME + ".")) {
                removed.add(modern.substring(TOOL_NAME.length() + 1));
            }
        }
        if (removed.isEmpty() || Collections.disjoint(removed, actions.keySet())) {
            return this;
        }
        Map<String, Tool> remaining = new LinkedHashMap<>(actions);
        remaining.keySet().removeAll(removed);
        return new CodeWorkspaceTool(remaining, confirmActions);
    }

    /**
     * A variant keeping only the named actions, or {@code this} when the list names the whole tool.
     * The mirror of {@link #denying} for sub-agent allowlists, which are expressed as what a task
     * may use rather than what it may not.
     */
    public CodeWorkspaceTool allowing(Collection<String> allowed) {
        if (allowed == null || allowed.contains(TOOL_NAME)) {
            return this;
        }
        Set<String> denied = new LinkedHashSet<>(actions.keySet());
        for (String action : actions.keySet()) {
            if (allowed.contains(TOOL_NAME + "." + action) || allowed.contains(action)) {
                denied.remove(action);
            }
        }
        return denying(denied);
    }

    /** Whether {@code name} addresses one of this variant's actions, in either spelling. */
    public boolean supports(String name) {
        String modern = modernize(name);
        return modern != null
                && modern.startsWith(TOOL_NAME + ".")
                && actions.containsKey(modern.substring(TOOL_NAME.length() + 1));
    }

    /**
     * False when every action has been denied. The caller must drop the tool entirely: an enum with
     * no values is not a tool, and an empty {@code required} enum is exactly the kind of thing
     * providers disagree about.
     */
    public boolean hasActions() {
        return !actions.isEmpty();
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = ToolArguments.objectSchema();
        // The ToolArguments helpers take the root schema and reach into /properties themselves;
        // handing them the properties node would nest the whole set one level too deep.
        ObjectNode properties = schema.withObject("/properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "Which operation to run. " + actionGuide());
        ArrayNode values = action.putArray("enum");
        actions.keySet().forEach(values::add);
        ToolArguments.required(schema, "action");

        ToolArguments.stringProperty(schema, "file_path",
                "read, edit and write: path of the file. Absolute, or relative to the workspace root.");
        ToolArguments.stringProperty(schema, "path",
                "glob, grep and tree: directory to work in. Absolute, or relative to the workspace root.");
        ToolArguments.stringProperty(schema, "pattern",
                "glob and grep: what to look for. A glob pattern for glob; a regular expression for grep.");
        ToolArguments.stringProperty(schema, "glob",
                "grep only: filename glob restricting which files are searched, e.g. **/*.java.");
        ToolArguments.stringProperty(schema, "output_mode",
                "grep only: content (matching lines, the default), files_with_matches, or count.");
        ToolArguments.intProperty(schema, "context",
                "grep only: lines of context around each match. Only used by output_mode=content.");
        ToolArguments.stringProperty(schema, "cursor",
                "glob only: next_cursor from the previous call, to fetch the following page. "
                        + "Only valid for the same pattern and path.");
        ToolArguments.intProperty(schema, "depth",
                "tree only: levels to show. Defaults to " + TreeTool.DEFAULT_DEPTH
                        + ", maximum " + TreeTool.MAX_DEPTH + ".");
        ToolArguments.intProperty(schema, "offset",
                "read only: 1-based line number to start from. Defaults to 1.");
        ToolArguments.intProperty(schema, "limit",
                "read: maximum lines to return. glob: page size.");
        ToolArguments.stringProperty(schema, "old_string",
                "edit only: exact text to replace. Must appear exactly once unless replace_all is true.");
        ToolArguments.stringProperty(schema, "new_string",
                "edit only: replacement text. May be empty to delete old_string.");
        ToolArguments.boolProperty(schema, "replace_all",
                "edit only: replace every occurrence instead of requiring a unique match.");
        ToolArguments.stringProperty(schema, "content",
                "write only: full file content as UTF-8 text.");

        return new ToolSpec(
                TOOL_NAME,
                description(),
                schema,
                // One capability tag is the hard limit (ToolSpec rejects two), and read and write are
                // not separable by a single tag. The writable variant therefore declares the stronger
                // of the two, which is also how the permission console manages it: one row, one name.
                writable() ? ToolCapability.MUTATION : ToolCapability.RETRIEVAL);
    }

    private String description() {
        StringBuilder text = new StringBuilder("Browse, search and read code in the local workspace. ")
                .append("Run one action per call and pass only the fields that action names.");
        if (writable()) {
            text.append(" edit and write change files on disk and are confined to the writable roots.");
        }
        return text.toString();
    }

    /** Names the actions this variant actually offers, so a narrowed tool never advertises the rest. */
    private String actionGuide() {
        String guides = actions.keySet().stream()
                .map(action -> switch (action) {
                    case "read" -> "read (file contents with line numbers: file_path, offset, limit)";
                    case "glob" -> "glob (find files by pattern: pattern, path, cursor, limit)";
                    case "grep" -> "grep (search contents by regex: pattern, path, glob, output_mode, context)";
                    case "tree" -> "tree (directory structure: path, depth)";
                    case "edit" -> "edit (replace an exact string: file_path, old_string, new_string, replace_all)";
                    case "write" -> "write (create a new file: file_path, content)";
                    default -> action;
                })
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        return "Available: " + guides + ".";
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String action = ToolArguments.requiredText(TOOL_NAME, arguments, "action");
        Tool delegate = actions.get(action);
        if (delegate == null) {
            throw new ToolExecutionException(TOOL_NAME, "unknown or unavailable action '" + action
                    + "'; available: " + String.join(", ", actions.keySet()));
        }
        // Verbatim, including the delegate's own ToolExecutionOutcome: its ResultStatus (EMPTY for a
        // search that matched nothing, and so on) is what the Inspector and the adaptive reflector
        // read, and re-wrapping it here would flatten every action back to AVAILABLE.
        //
        // The delegate reports failures under its own name — "Tool [read]: …" — which the model did
        // not call. The sub-tool constants cannot be renamed to hide that: the project-discovery scan
        // registers those classes as real tools under those names. The message stays actionable.
        return delegate.executeOutcome(arguments);
    }

    /**
     * The confirmation half of the legacy-name migration: an operator who listed {@code edit} or
     * {@code write} in {@code HARNESS_RISK_CONFIRM_TOOLS} still gets asked.
     */
    @Override
    public boolean requiresConfirmation(JsonNode arguments) {
        if (confirmActions.isEmpty()) {
            return false;
        }
        if (confirmActions.contains(TOOL_NAME)) {
            return true;
        }
        JsonNode action = arguments == null ? null : arguments.get("action");
        if (action == null || !action.isTextual()) {
            return false;
        }
        String name = action.asText();
        return confirmActions.contains(TOOL_NAME + "." + name) || confirmActions.contains(name);
    }

    @Override
    public String confirmationSummary(JsonNode arguments) {
        String action = arguments == null ? null : text(arguments.get("action"));
        String target = arguments == null
                ? null
                : firstNonNull(
                        text(arguments.get("file_path")),
                        text(arguments.get("path")),
                        text(arguments.get("pattern")));
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

    /** Whether this variant offers a write action. */
    public boolean writable() {
        return actions.keySet().stream().anyMatch(WRITE_ACTIONS::contains);
    }

    /** The actions this variant offers, in schema order. */
    public Set<String> availableActions() {
        return actions.keySet();
    }
}
