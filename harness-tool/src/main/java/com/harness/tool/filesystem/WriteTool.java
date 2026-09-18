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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Create a new file. Never overwrites.
 *
 * <p>Overwriting is what {@link EditTool} is for: requiring a read-then-targeted-edit keeps a stale
 * view from silently destroying content, and it means this tool never needs a confirmation step to
 * be safe.
 *
 * <p>The real defence against writing through a link is the {@code CREATE_NEW} open, not the
 * existence check. {@code Files.exists} follows links and reports false for a dangling symlink,
 * which would let a link pointing outside the workspace look like an absent file; {@code O_CREAT |
 * O_EXCL} refuses such a name outright. The check is here for the error message.
 */
public final class WriteTool implements Tool {

    public static final String TOOL_NAME = "write";

    private final FileSystemAccessPolicy policy;
    private final FileSystemWorkspace workspace;

    public WriteTool(FileSystemAccessPolicy policy, FileSystemWorkspace workspace) {
        this.policy = policy;
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = ToolArguments.objectSchema();
        ToolArguments.stringProperty(schema, "file_path",
                "Path of the file to create. Absolute, or relative to the workspace root. "
                        + "Its parent directory must already exist.");
        ToolArguments.stringProperty(schema, "content", "Full file content as UTF-8 text.");
        ToolArguments.required(schema, "file_path", "content");
        return new ToolSpec(
                TOOL_NAME,
                "Create a new file. Refuses if the file already exists — use edit to change an "
                        + "existing file. Parent directories are not created automatically.",
                schema,
                ToolCapability.MUTATION);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        if (!workspace.writable()) {
            throw new ToolExecutionException(TOOL_NAME, "writing is disabled in this scope");
        }
        Path target = policy.resolveWritable(TOOL_NAME, workspace.resolve(TOOL_NAME,
                ToolArguments.requiredText(TOOL_NAME, arguments, "file_path")));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw alreadyExists(target);
        }

        String content = ToolArguments.requiredTextAllowingEmpty(
                TOOL_NAME, arguments, "content");
        try {
            Files.writeString(target, content,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw alreadyExists(target);
        } catch (IOException e) {
            throw new ToolExecutionException(
                    TOOL_NAME, "failed to create " + target + ": " + e.getMessage(), e);
        }
        return ToolExecutionOutcome.succeeded(
                ToolOutput.text("Created " + target + " (" + content.length() + " characters)"),
                ResultStatus.AVAILABLE);
    }

    private static ToolExecutionException alreadyExists(Path target) {
        return new ToolExecutionException(TOOL_NAME,
                target + " already exists; use edit to modify an existing file");
    }
}
