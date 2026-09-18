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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Show the shape of a directory tree.
 *
 * <p>This exists because "understand the project layout" and "enumerate files matching a pattern"
 * are different questions, and answering the first with the second means paging hundreds of paths
 * into the context. A depth-limited tree answers it in a couple of dozen lines.
 *
 * <p>Deliberately cheap and bounded: one directory read per shown level, no recursion beyond
 * {@code depth}, and no file counts. Counts would mean walking every subtree to decorate the
 * output, which is exactly the unbounded work this tool exists to avoid — use {@code glob} or
 * {@code grep} when a number is actually wanted.
 */
public final class TreeTool implements Tool {

    public static final String TOOL_NAME = "tree";

    // package-private: CodeWorkspaceTool's merged schema restates these bounds, and a second
    // hard-coded copy would drift the moment one of them changes.
    static final int DEFAULT_DEPTH = 2;
    static final int MAX_DEPTH = 6;

    /** Entries rendered per call. Bounds the output and the reads that produce it. */
    private static final int MAX_NODES = 2000;

    private final FileSystemAccessPolicy policy;
    private final FileSystemWorkspace workspace;

    public TreeTool(FileSystemAccessPolicy policy, FileSystemWorkspace workspace) {
        this.policy = policy;
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = ToolArguments.objectSchema();
        ToolArguments.stringProperty(schema, "path",
                "Directory to browse. Absolute, or relative to the workspace root. "
                        + "Defaults to " + workspace.root() + ".");
        ToolArguments.intProperty(schema, "depth",
                "Levels to show. Defaults to " + DEFAULT_DEPTH + ", maximum " + MAX_DEPTH + ".");
        return new ToolSpec(
                TOOL_NAME,
                "Show the directory structure of a path as an indented tree. Directories end in '/'. "
                        + "Skips .git, target, node_modules, build and dist, and does not follow "
                        + "links. Shows structure only — no file counts. At most " + MAX_NODES
                        + " entries; use glob or grep to enumerate files.",
                schema,
                ToolCapability.RETRIEVAL);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String pathArgument = ToolArguments.optionalText(arguments, "path");
        Path root = policy.resolveReadable(TOOL_NAME, pathArgument == null
                ? workspace.root()
                : workspace.resolve(TOOL_NAME, pathArgument));
        if (!Files.isDirectory(root)) {
            throw new ToolExecutionException(TOOL_NAME, "not a directory: " + root);
        }
        int depth = ToolArguments.optionalInt(TOOL_NAME, arguments, "depth", DEFAULT_DEPTH);
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new ToolExecutionException(TOOL_NAME,
                    "depth must be between 1 and " + MAX_DEPTH);
        }

        Walk walk = new Walk();
        collect(root, "", 0, depth, walk);

        StringBuilder text = new StringBuilder();
        text.append(root).append('\n');
        walk.lines.forEach(line -> text.append(line).append('\n'));
        if (walk.truncated) {
            text.append("(truncated at ").append(MAX_NODES)
                    .append(" entries — lower depth or point at a subdirectory)\n");
        }
        return ToolExecutionOutcome.succeeded(
                ToolOutput.text(text.toString()),
                walk.lines.isEmpty() ? ResultStatus.EMPTY : ResultStatus.AVAILABLE);
    }

    private void collect(Path dir, String prefix, int depth, int maxDepth, Walk walk) {
        if (depth >= maxDepth || walk.remaining <= 0) {
            return;
        }
        Entry entry = read(dir);
        if (entry == null) {
            walk.lines.add(prefix + "└── (contents unreadable)");
            walk.remaining--;
            return;
        }

        for (int i = 0; i < entry.children().size(); i++) {
            if (walk.remaining <= 0) {
                walk.truncated = true;
                return;
            }
            Child child = entry.children().get(i);
            boolean last = i == entry.children().size() - 1;
            walk.lines.add(prefix + (last ? "└── " : "├── ") + child.label());
            walk.remaining--;
            if (child.directory()) {
                collect(child.path(), prefix + (last ? "    " : "│   "), depth + 1, maxDepth, walk);
            }
        }
    }

    /** Directories, then links, then files — each alphabetical. Null when the directory fails to read. */
    private static Entry read(Path dir) {
        List<Child> directories = new ArrayList<>();
        List<Child> links = new ArrayList<>();
        List<Child> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    // Shown, never descended: a junction is an other, not a symlink, on Windows, and
                    // following either would take the listing outside the workspace.
                    links.add(new Child(path, name + "@", false));
                } else if (attributes.isDirectory()) {
                    if (!NioSearchBackend.SKIP_DIRS.contains(name)) {
                        directories.add(new Child(path, name + '/', true));
                    }
                } else {
                    files.add(new Child(path, name, false));
                }
            }
        } catch (IOException e) {
            return null;
        }
        Comparator<Child> byLabel = Comparator.comparing(Child::label);
        directories.sort(byLabel);
        links.sort(byLabel);
        files.sort(byLabel);
        List<Child> ordered = new ArrayList<>(directories);
        ordered.addAll(links);
        ordered.addAll(files);
        return new Entry(ordered);
    }

    private record Child(Path path, String label, boolean directory) {
    }

    private record Entry(List<Child> children) {
    }

    private static final class Walk {
        private final List<String> lines = new ArrayList<>();
        private int remaining = MAX_NODES;
        private boolean truncated;
    }
}
