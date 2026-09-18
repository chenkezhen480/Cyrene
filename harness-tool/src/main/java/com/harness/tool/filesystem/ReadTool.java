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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read a local text file and return it verbatim with line numbers.
 *
 * <p>Deliberately not the same tool as {@code read_file}: that one resolves uploaded artifacts and
 * summarises them through a model. This one returns raw bytes-as-text and never invokes a model —
 * the caller wants the file as it is, to reason about or to edit.
 */
public final class ReadTool implements Tool {

    public static final String TOOL_NAME = "read";

    private static final int BINARY_SNIFF_BYTES = 8192;

    private final FileSystemAccessPolicy policy;
    private final FileSystemWorkspace workspace;

    public ReadTool(FileSystemAccessPolicy policy, FileSystemWorkspace workspace) {
        this.policy = policy;
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        FileSystemAccessPolicy.Settings settings = policy.settings();
        ObjectNode schema = ToolArguments.objectSchema();
        ToolArguments.stringProperty(schema, "file_path",
                "Path of the file to read. Absolute, or relative to the workspace root.");
        ToolArguments.intProperty(schema, "offset",
                "1-based line number to start reading from. Defaults to 1.");
        ToolArguments.intProperty(schema, "limit",
                "Maximum number of lines to return. Defaults to " + settings.readMaxLines() + ".");
        ToolArguments.required(schema, "file_path");
        return new ToolSpec(
                TOOL_NAME,
                "Read a text file from the local filesystem and return its contents with line numbers. "
                        + "Reads at most " + settings.readMaxLines() + " lines from offset; use offset "
                        + "to page through a longer file. Binary files are rejected.",
                schema,
                ToolCapability.READ);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        Path requested = workspace.resolve(TOOL_NAME,
                ToolArguments.requiredText(TOOL_NAME, arguments, "file_path"));
        Path file = policy.resolveReadable(TOOL_NAME, requested);
        if (!Files.isRegularFile(file)) {
            throw new ToolExecutionException(TOOL_NAME, "not a regular file: " + file);
        }
        if (isBinary(file)) {
            throw new ToolExecutionException(TOOL_NAME,
                    "binary file, refusing to read as text: " + file);
        }

        FileSystemAccessPolicy.Settings settings = policy.settings();
        int offset = Math.max(1, ToolArguments.optionalInt(
                TOOL_NAME, arguments, "offset", 1));
        int limit = ToolArguments.optionalInt(
                TOOL_NAME, arguments, "limit", settings.readMaxLines());
        if (limit <= 0) {
            throw new ToolExecutionException(TOOL_NAME, "limit must be greater than 0");
        }

        List<String> lines = new ArrayList<>();
        long bytes = 0;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int lineNumber = 0;
            while (true) {
                String line = nextLine(reader, settings.maxOutputBytes());
                if (line == null) {
                    break;
                }
                lineNumber++;
                if (lineNumber < offset) {
                    continue;
                }
                bytes += line.length() + 1;
                if (lines.size() >= limit || bytes > settings.maxOutputBytes()) {
                    truncated = true;
                    break;
                }
                lines.add(line);
            }
        } catch (IOException e) {
            throw new ToolExecutionException(
                    TOOL_NAME, "failed to read " + file + ": " + e.getMessage(), e);
        }

        if (lines.isEmpty()) {
            return ToolExecutionOutcome.succeeded(
                    ToolOutput.text("(no content at offset " + offset + " in " + file + ")"),
                    ResultStatus.EMPTY);
        }
        return ToolExecutionOutcome.succeeded(
                ToolOutput.text(render(file, lines, offset, truncated)), ResultStatus.AVAILABLE);
    }

    private static String render(Path file, List<String> lines, int offset, boolean truncated) {
        StringBuilder text = new StringBuilder();
        text.append(file).append('\n');
        int width = String.valueOf(offset + lines.size()).length();
        for (int i = 0; i < lines.size(); i++) {
            text.append(String.format("%" + width + "d\t%s%n", offset + i, lines.get(i)));
        }
        if (truncated) {
            text.append("(truncated — continue with offset ")
                    .append(offset + lines.size()).append(')').append('\n');
        }
        return text.toString();
    }

    /**
     * One line, but a single pathological line (minified bundle, generated blob) cannot allocate
     * unbounded memory: past {@code maxChars} the rest of that line is dropped rather than held.
     *
     * @return the line without its terminator, or null at end of file
     */
    private static String nextLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder();
        int read;
        boolean any = false;
        while ((read = reader.read()) != -1) {
            any = true;
            if (read == '\n') {
                return line.toString();
            }
            if (read == '\r') {
                reader.mark(1);
                if (reader.read() != '\n') {
                    reader.reset();
                }
                return line.toString();
            }
            if (line.length() < maxChars) {
                line.append((char) read);
            }
        }
        return any ? line.toString() : null;
    }

    private static boolean isBinary(Path file) {
        byte[] head = new byte[BINARY_SNIFF_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, head.length);
            for (int i = 0; i < read; i++) {
                if (head[i] == 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            throw new ToolExecutionException(
                    TOOL_NAME, "failed to read " + file + ": " + e.getMessage(), e);
        }
        return false;
    }
}
