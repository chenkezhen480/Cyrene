package com.harness.tool.filesystem;

import com.harness.core.exception.ToolExecutionException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * One run's filesystem scope: the base directory relative paths resolve against, and whether this
 * scope may write at all.
 *
 * <p>The project-discovery scan runs with a read-only workspace rooted at its {@code sourceRoot}.
 * It shares this package's tools with normal sessions; being confined and non-writable is what
 * keeps it from reading or changing anything outside that directory.
 *
 * @param root     absolute base directory for relative arguments and for omitted {@code path}
 * @param writable false makes {@code edit} / {@code write} refuse outright
 */
public record FileSystemWorkspace(Path root, boolean writable) {

    public static FileSystemWorkspace host(Path root) {
        return new FileSystemWorkspace(root, true);
    }

    public static FileSystemWorkspace readOnly(Path root) {
        return new FileSystemWorkspace(root, false);
    }

    /**
     * Absolute paths pass through untouched; a relative path resolves against this workspace root.
     * Drive-relative ({@code D:foo}) and current-drive-relative ({@code \foo}) forms are refused
     * rather than guessed at — they resolve against per-drive process state that no caller controls.
     */
    public Path resolve(String toolName, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ToolExecutionException(toolName, "path is required");
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException e) {
            throw new ToolExecutionException(
                    toolName, "invalid path (" + e.getReason() + "): " + raw, e);
        }
        if (path.isAbsolute()) {
            return path;
        }
        if (path.getRoot() != null) {
            throw new ToolExecutionException(toolName, "path must be absolute: " + raw);
        }
        return root.resolve(path);
    }
}
