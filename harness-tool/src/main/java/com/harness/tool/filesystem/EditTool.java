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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Exact-string replacement in an existing file.
 *
 * <p>The {@code old_string} → {@code new_string} shape is what makes concurrent modification safe to
 * ignore: a stale read cannot silently overwrite, because if the text the caller saw is no longer
 * there the match fails and nothing is written. Zero matches and ambiguous matches both fail rather
 * than guess.
 *
 * <p>Line endings are preserved by aligning the <em>arguments</em> to the file, never by rewriting
 * the file to one convention — a whole-file normalise would flatten mixed-ending files and produce a
 * diff touching lines nobody asked about.
 */
public final class EditTool implements Tool {

    public static final String TOOL_NAME = "edit";

    private final FileSystemAccessPolicy policy;
    private final FileSystemWorkspace workspace;

    public EditTool(FileSystemAccessPolicy policy, FileSystemWorkspace workspace) {
        this.policy = policy;
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = ToolArguments.objectSchema();
        ToolArguments.stringProperty(schema, "file_path",
                "Path of the file to modify. Absolute, or relative to the workspace root.");
        ToolArguments.stringProperty(schema, "old_string",
                "Exact text to replace, including whitespace. Must appear exactly once unless "
                        + "replace_all is true.");
        ToolArguments.stringProperty(schema, "new_string",
                "Replacement text. May be empty to delete old_string.");
        ToolArguments.boolProperty(schema, "replace_all",
                "Replace every occurrence instead of requiring a unique match. Defaults to false.");
        ToolArguments.required(schema, "file_path", "old_string", "new_string");
        return new ToolSpec(
                TOOL_NAME,
                "Replace an exact string in an existing file. Fails if old_string is absent, or if it "
                        + "appears more than once and replace_all is false. The file's existing line "
                        + "endings are preserved and the write is atomic.",
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
            throw new ToolExecutionException(TOOL_NAME, "editing is disabled in this scope");
        }
        Path requested = workspace.resolve(TOOL_NAME,
                ToolArguments.requiredText(TOOL_NAME, arguments, "file_path"));
        Path target = policy.resolveWritable(TOOL_NAME, requested);
        policy.requireReplaceableRegularFile(TOOL_NAME, target);

        FileSystemAccessPolicy.Settings settings = policy.settings();
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new ToolExecutionException(
                    TOOL_NAME, "cannot stat " + target + ": " + e.getMessage(), e);
        }
        if (size > settings.editMaxFileBytes()) {
            throw new ToolExecutionException(TOOL_NAME, "file is " + size
                    + " bytes, above the " + settings.editMaxFileBytes() + " byte edit limit: " + target);
        }

        String before = readStrict(target);
        boolean crlf = usesCrlf(before);
        // Allowed to be empty here so applyEdit can explain why that is invalid, rather than the
        // argument reader reporting the parameter as merely absent.
        String oldString = alignEol(
                ToolArguments.requiredTextAllowingEmpty(TOOL_NAME, arguments, "old_string"), crlf);
        String newString = alignEol(ToolArguments.requiredTextAllowingEmpty(
                TOOL_NAME, arguments, "new_string"), crlf);

        String after = applyEdit(before, oldString, newString,
                ToolArguments.optionalBool(arguments, "replace_all", false), TOOL_NAME);
        if (after.equals(before)) {
            throw new ToolExecutionException(TOOL_NAME,
                    "old_string and new_string produce no change");
        }
        atomicReplace(target, after);

        int replaced = countOccurrences(before, oldString);
        return ToolExecutionOutcome.succeeded(
                ToolOutput.text("Updated " + target
                        + (replaced > 1 ? " (" + replaced + " replacements)" : "")),
                ResultStatus.AVAILABLE);
    }

    // ────────────────────────────────────────────────────────────────── edit mechanics

    /** The file's dominant ending is CRLF; ties and LF-only files stay LF. */
    static boolean usesCrlf(String text) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return crlf > lf;
    }

    /**
     * Align caller-supplied text to the file's convention. Handles CRLF on input too, which is not
     * paranoia: the caller usually copied the text out of a {@code read} of this very file, so it
     * carries whatever the file has.
     */
    static String alignEol(String text, boolean crlf) {
        String lf = text.replace("\r\n", "\n");
        return crlf ? lf.replace("\n", "\r\n") : lf;
    }

    static String applyEdit(String text, String oldString, String newString, boolean replaceAll,
                            String toolName) {
        if (oldString.isEmpty()) {
            throw new ToolExecutionException(toolName, "old_string must not be empty");
        }
        if (replaceAll) {
            if (text.indexOf(oldString) < 0) {
                throw notFound(toolName, text);
            }
            return text.replace(oldString, newString);
        }
        int first = text.indexOf(oldString);
        if (first < 0) {
            throw notFound(toolName, text);
        }
        // Search from first + 1, not first + length: for "aa" inside "aaa" this reports ambiguous
        // where a length-based scan would report unique, and refusing is the safer failure.
        if (text.indexOf(oldString, first + 1) >= 0) {
            throw new ToolExecutionException(toolName,
                    "old_string appears more than once; add surrounding context to make it unique, "
                            + "or set replace_all");
        }
        return text.substring(0, first) + newString + text.substring(first + oldString.length());
    }

    private static ToolExecutionException notFound(String toolName, String text) {
        String hint = text.indexOf('\r') >= 0 && !text.contains("\r\n")
                ? " (this file uses bare CR line endings, which old_string cannot reproduce)"
                : " (check exact whitespace and line endings)";
        return new ToolExecutionException(toolName, "old_string not found" + hint);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /**
     * Strict UTF-8: a malformed sequence throws rather than being replaced with U+FFFD, so a GBK or
     * UTF-16 file is refused instead of silently corrupted. A UTF-8 BOM survives because nothing
     * here strips or trims the content.
     */
    private static String readStrict(Path target) {
        try {
            return Files.readString(target);
        } catch (IOException e) {
            throw new ToolExecutionException(TOOL_NAME,
                    "cannot read " + target + " as UTF-8 text: " + e.getMessage(), e);
        }
    }

    /**
     * Write through a sibling temp file and rename it over the target.
     *
     * <p>Sibling, not temp directory: same volume is what makes the rename real rather than a copy,
     * and it is the only reason {@code ATOMIC_MOVE} cannot fail here. There is deliberately no
     * non-atomic fallback — on Windows that path deletes the target first and then renames, so a
     * crash between the two loses the file outright, which is strictly worse than failing.
     */
    private static void atomicReplace(Path target, String content) {
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), ".cyrene-edit", ".tmp");
            carryOverPermissions(target, temp);
            Files.writeString(temp, content);
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (IOException e) {
            // A sharing violation (editor, antivirus) surfaces here. Failing is right: editing in
            // place instead would trade a retryable error for a torn file.
            throw new ToolExecutionException(TOOL_NAME,
                    "failed to replace " + target + ": " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the original failure is the one that matters
                }
            }
        }
    }

    /** A fresh temp file is 0600; the replaced file must keep whatever it had. */
    private static void carryOverPermissions(Path target, Path temp) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
            Files.setPosixFilePermissions(temp, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows or a filesystem without POSIX permissions: nothing to carry over
        }
    }
}
