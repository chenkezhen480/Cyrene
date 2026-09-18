package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Search file contents by regular expression.
 *
 * <p>Uses ripgrep when it is installed and plain NIO otherwise; both backends search hidden and
 * gitignored files, so results do not depend on which one answered. The regex is case-sensitive,
 * matching ripgrep's default — use an inline {@code (?i)} to widen it.
 */
public final class GrepTool implements Tool {

    public static final String TOOL_NAME = "grep";

    private static final Set<String> OUTPUT_MODES = Set.of("content", "files_with_matches", "count");

    private final FileSystemAccessPolicy policy;
    private final FileSystemWorkspace workspace;

    public GrepTool(FileSystemAccessPolicy policy, FileSystemWorkspace workspace) {
        this.policy = policy;
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = ToolArguments.objectSchema();
        ToolArguments.stringProperty(schema, "pattern", "Regular expression (case-sensitive).");
        ToolArguments.stringProperty(schema, "path",
                "File or directory to search. Absolute, or relative to the workspace root. "
                        + "Defaults to " + workspace.root() + ".");
        ToolArguments.stringProperty(schema, "glob",
                "Optional filename filter, e.g. '*.java' or '**/*.yml'.");
        ToolArguments.stringProperty(schema, "output_mode",
                "One of content (matching lines, the default), files_with_matches, count.");
        ToolArguments.intProperty(schema, "context",
                "Lines of context around each match. Only used by output_mode=content. Defaults to 0.");
        ToolArguments.required(schema, "pattern");
        return new ToolSpec(
                TOOL_NAME,
                "Search file contents by regular expression. Returns at most "
                        + policy.settings().maxResults()
                        + " matching lines. Hidden files are searched; .git, target, node_modules, "
                        + "build and dist are skipped. Backend: " + policy.searchBackend().name() + ".",
                schema,
                ToolCapability.RETRIEVAL);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String pattern = ToolArguments.requiredText(TOOL_NAME, arguments, "pattern");
        String pathArgument = ToolArguments.optionalText(arguments, "path");
        Path root = policy.resolveReadable(TOOL_NAME, pathArgument == null
                ? workspace.root()
                : workspace.resolve(TOOL_NAME, pathArgument));

        String outputMode = ToolArguments.optionalText(arguments, "output_mode");
        outputMode = outputMode == null ? "content" : outputMode.trim();
        if (!OUTPUT_MODES.contains(outputMode)) {
            throw new ToolExecutionException(TOOL_NAME,
                    "output_mode must be one of " + OUTPUT_MODES + ", got: " + outputMode);
        }
        int context = Math.max(0, ToolArguments.optionalInt(TOOL_NAME, arguments, "context", 0));

        if (Files.isRegularFile(root)) {
            Path parent = root.getParent();
            return search(parent, root.getFileName().toString(), pattern, outputMode, context,
                    ToolArguments.optionalText(arguments, "glob"));
        }
        if (!Files.isDirectory(root)) {
            throw new ToolExecutionException(TOOL_NAME, "not a file or directory: " + root);
        }
        return search(root, null, pattern, outputMode, context,
                ToolArguments.optionalText(arguments, "glob"));
    }

    private ToolExecutionOutcome search(Path searchRoot, String singleFile, String pattern,
                                        String outputMode, int context, String fileGlob) {
        // A single-file search is expressed as a filename glob so both backends keep one entry point.
        String effectiveGlob = singleFile != null ? singleFile : fileGlob;
        // files_with_matches and count never print context, so do not pay to collect it.
        int contextLines = "content".equals(outputMode) ? context : 0;

        FileSearchBackend.GrepResult result = policy.searchBackend().grep(
                searchRoot, pattern, effectiveGlob, contextLines,
                policy.settings().maxResults(), policy.settings().maxOutputBytes());
        List<FileSearchBackend.GrepFileResult> results = result.files();
        if (results.isEmpty()) {
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text("No matches for /" + pattern + "/ under " + searchRoot),
                    ResultStatus.EMPTY);
        }

        String truncated = result.truncated()
                ? "(output truncated at " + policy.settings().maxOutputBytes()
                        + " bytes; narrow the pattern or path)\n"
                : "";
        return switch (outputMode) {
            case "files_with_matches" -> ToolExecutionOutcome.succeeded(
                    ToolOutput.text(renderFiles(results) + truncated), ResultStatus.AVAILABLE);
            case "count" -> ToolExecutionOutcome.succeeded(
                    ToolOutput.text(renderCounts(results) + truncated), ResultStatus.AVAILABLE);
            default -> ToolExecutionOutcome.succeeded(
                    ToolOutput.text(renderContent(results, policy.settings().maxResults()) + truncated),
                    ResultStatus.AVAILABLE);
        };
    }

    private static String renderFiles(List<FileSearchBackend.GrepFileResult> results) {
        StringBuilder text = new StringBuilder();
        text.append(results.size()).append(" files:\n");
        for (FileSearchBackend.GrepFileResult result : results) {
            text.append(result.path()).append('\n');
        }
        return text.toString();
    }

    private static String renderCounts(List<FileSearchBackend.GrepFileResult> results) {
        StringBuilder text = new StringBuilder();
        int total = 0;
        for (FileSearchBackend.GrepFileResult result : results) {
            total += result.matches().size();
            text.append(result.path()).append(':').append(result.matches().size()).append('\n');
        }
        text.append("total: ").append(total).append('\n');
        return text.toString();
    }

    /** grep-shaped output: {@code path:N:text} for a match, {@code path:N-text} for context. */
    private static String renderContent(List<FileSearchBackend.GrepFileResult> results, int maxResults) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (FileSearchBackend.GrepFileResult result : results) {
            for (FileSearchBackend.GrepLine line : result.matches()) {
                if (shown >= maxResults) {
                    break;
                }
                int first = line.lineNumber() - line.before().size();
                for (int i = 0; i < line.before().size(); i++) {
                    text.append(result.path()).append(':').append(first + i).append('-')
                            .append(line.before().get(i)).append('\n');
                }
                text.append(result.path()).append(':').append(line.lineNumber()).append(':')
                        .append(line.text()).append('\n');
                for (int i = 0; i < line.after().size(); i++) {
                    text.append(result.path()).append(':').append(line.lineNumber() + 1 + i).append('-')
                            .append(line.after().get(i)).append('\n');
                }
                shown++;
            }
        }
        if (shown >= maxResults) {
            text.append("(limited to ").append(maxResults).append(" matching lines)\n");
        }
        return text.toString();
    }
}
