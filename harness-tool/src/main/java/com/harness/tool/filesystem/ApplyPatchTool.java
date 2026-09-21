package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Applies one validated multi-file patch inside the configured writable roots. */
public final class ApplyPatchTool implements Tool {

    public static final String TOOL_NAME = "patch";

    // ponytail: one process-wide lock keeps multi-file commits simple; use per-root locks only if
    // concurrent coding sessions become measurable contention.
    private static final Object PATCH_LOCK = new Object();

    private final FileSystemAccessPolicy policy;
    private final FileSystemWorkspace workspace;

    public ApplyPatchTool(FileSystemAccessPolicy policy, FileSystemWorkspace workspace) {
        this.policy = policy;
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = ToolArguments.objectSchema();
        ToolArguments.stringProperty(schema, "patch",
                "Patch text using *** Update File, *** Add File and *** Delete File sections. "
                        + "Update sections contain one or more @@ hunks with space, + and - lines.");
        ToolArguments.required(schema, "patch");
        return new ToolSpec(
                TOOL_NAME,
                "Atomically validate and apply a UTF-8 patch to one or more workspace files. "
                        + "Use this as the default way to modify existing code.",
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
            throw new ToolExecutionException(TOOL_NAME, "patching is disabled in this scope");
        }
        String patch = ToolArguments.requiredTextAllowingEmpty(TOOL_NAME, arguments, "patch");
        synchronized (PATCH_LOCK) {
            List<PreparedChange> changes = prepare(parse(patch));
            commit(changes);
            return outcome(changes);
        }
    }

    private List<PreparedChange> prepare(List<PatchChange> parsed) {
        List<PreparedChange> prepared = new ArrayList<>();
        Set<Path> targets = new LinkedHashSet<>();
        for (PatchChange change : parsed) {
            Path target = policy.resolveWritableAllowingMissingParents(TOOL_NAME,
                    workspace.resolve(TOOL_NAME, change.path()));
            if (!targets.add(target)) {
                throw new ToolExecutionException(TOOL_NAME,
                        "a file may appear only once in a patch: " + target);
            }
            prepared.add(switch (change.operation()) {
                case ADD -> prepareAdd(change, target);
                case UPDATE -> prepareUpdate(change, target);
                case DELETE -> prepareDelete(change, target);
            });
        }
        checkStructuralConflicts(targets);
        return List.copyOf(prepared);
    }

    private void checkStructuralConflicts(Set<Path> targets) {
        for (Path target : targets) {
            for (Path ancestor = target.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                if (targets.contains(ancestor)) {
                    throw new ToolExecutionException(TOOL_NAME,
                            "path conflict: a path cannot be both a file and a directory: "
                                    + ancestor + " and " + target);
                }
            }
        }
    }

    private PreparedChange prepareAdd(PatchChange change, Path target) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolExecutionException(TOOL_NAME, target + " already exists");
        }
        for (Path ancestor = target.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(ancestor)) {
                    throw new ToolExecutionException(TOOL_NAME,
                            "refusing to create file under symbolic link: " + ancestor);
                }
                if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ToolExecutionException(TOOL_NAME,
                            "cannot create file " + target + " because parent path "
                                    + ancestor + " is an existing non-directory");
                }
                break;
            }
        }
        return new PreparedChange(change.operation(), target, null, change.addedContent(),
                lineCount(change.addedContent()), 0);
    }

    private PreparedChange prepareUpdate(PatchChange change, Path target) {
        String before = readExisting(target);
        String normalized = normalizeLf(before);
        List<String> lines = new ArrayList<>(List.of(normalized.split("\\n", -1)));
        int additions = 0;
        int deletions = 0;
        for (Hunk hunk : change.hunks()) {
            int index = uniqueIndexOf(lines, hunk.oldLines(), target);
            lines.subList(index, index + hunk.oldLines().size()).clear();
            lines.addAll(index, hunk.newLines());
            additions += hunk.additions();
            deletions += hunk.deletions();
        }
        String after = String.join("\n", lines);
        if (EditTool.usesCrlf(before)) {
            after = after.replace("\n", "\r\n");
        }
        if (after.equals(before)) {
            throw new ToolExecutionException(TOOL_NAME, "patch produces no change: " + target);
        }
        return new PreparedChange(change.operation(), target, before, after, additions, deletions);
    }

    private PreparedChange prepareDelete(PatchChange change, Path target) {
        String before = readExisting(target);
        return new PreparedChange(change.operation(), target, before, null,
                0, lineCount(normalizeLf(before)));
    }

    private String readExisting(Path target) {
        policy.requireReplaceableRegularFile(TOOL_NAME, target);
        try {
            long size = Files.size(target);
            if (size > policy.settings().editMaxFileBytes()) {
                throw new ToolExecutionException(TOOL_NAME, "file is " + size + " bytes, above the "
                        + policy.settings().editMaxFileBytes() + " byte patch limit: " + target);
            }
            return Files.readString(target);
        } catch (IOException e) {
            throw new ToolExecutionException(
                    TOOL_NAME, "cannot read " + target + " as UTF-8 text: " + e.getMessage(), e);
        }
    }

    /** Stage every new body first, then rename entries with rollback on any commit failure. */
    private void commit(List<PreparedChange> changes) {
        List<CommitEntry> entries = new ArrayList<>();
        List<Path> createdDirectories = new ArrayList<>();
        try {
            ensureDirectories(changes, createdDirectories);
            for (PreparedChange change : changes) {
                Path staged = change.after() == null ? null : stage(change);
                entries.add(new CommitEntry(change, staged));
            }
            for (CommitEntry entry : entries) {
                PreparedChange change = entry.change;
                if (change.before() != null) {
                    entry.backup = unusedSibling(change.target(), ".cyrene-patch-backup");
                    Files.move(change.target(), entry.backup, StandardCopyOption.ATOMIC_MOVE);
                }
                if (entry.staged != null) {
                    Files.move(entry.staged, change.target(), StandardCopyOption.ATOMIC_MOVE);
                    entry.staged = null;
                }
                entry.applied = true;
            }
            for (CommitEntry entry : entries) {
                deleteQuietly(entry.backup);
                entry.backup = null;
            }
        } catch (IOException | RuntimeException failure) {
            IOException rollbackFailure = rollback(entries, createdDirectories);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            if (failure instanceof ToolExecutionException toolFailure) {
                throw toolFailure;
            }
            throw new ToolExecutionException(
                    TOOL_NAME, "failed to commit patch: " + failure.getMessage(), failure);
        } finally {
            for (CommitEntry entry : entries) {
                deleteQuietly(entry.staged);
                deleteQuietly(entry.backup);
            }
        }
    }

    private void ensureDirectories(List<PreparedChange> changes, List<Path> createdDirectories) throws IOException {
        for (PreparedChange change : changes) {
            Path target = change.target();
            Path parent = target.getParent();
            if (parent == null) {
                continue;
            }
            // 1. Re-validate all existing ancestors (anti-TOCTOU, anti-symlink)
            for (Path ancestor = parent; ancestor != null; ancestor = ancestor.getParent()) {
                if (!policy.isWithinWritableRoots(ancestor)) {
                    break;
                }
                if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(ancestor)) {
                        throw new ToolExecutionException(TOOL_NAME,
                                "refusing to traverse symbolic link: " + ancestor);
                    }
                    if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                        throw new ToolExecutionException(TOOL_NAME,
                                "path is not a directory: " + ancestor);
                    }
                }
            }
            // 2. Identify missing directories from existing ancestor down to parent
            List<Path> missing = new ArrayList<>();
            for (Path p = parent; p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS); p = p.getParent()) {
                missing.add(0, p);
            }
            for (Path dir : missing) {
                if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                    Path dirParent = dir.getParent();
                    if (dirParent != null) {
                        if (Files.isSymbolicLink(dirParent)) {
                            throw new ToolExecutionException(TOOL_NAME,
                                    "refusing to create directory inside symbolic link: " + dirParent);
                        }
                        policy.requireWithinWritableRoots(TOOL_NAME, dirParent);
                    }
                    Files.createDirectory(dir);
                    createdDirectories.add(dir);
                    policy.requireWithinWritableRoots(TOOL_NAME, dir);
                }
            }
        }
    }

    private Path stage(PreparedChange change) throws IOException {
        Path staged = null;
        try {
            staged = Files.createTempFile(change.target().getParent(), ".cyrene-patch", ".tmp");
            if (change.before() != null) {
                carryOverPermissions(change.target(), staged);
            }
            Files.writeString(staged, change.after());
            return staged;
        } catch (IOException e) {
            deleteQuietly(staged);
            throw e;
        }
    }

    private static Path unusedSibling(Path target, String prefix) throws IOException {
        Path slot = Files.createTempFile(target.getParent(), prefix, ".tmp");
        Files.delete(slot);
        return slot;
    }

    private static IOException rollback(List<CommitEntry> entries, List<Path> createdDirectories) {
        IOException first = null;
        // 1. Clean up staged files first so directories aren't kept non-empty by them
        for (CommitEntry entry : entries) {
            deleteQuietly(entry.staged);
            entry.staged = null;
        }
        // 2. Restore backups and delete applied target files
        for (int i = entries.size() - 1; i >= 0; i--) {
            CommitEntry entry = entries.get(i);
            try {
                if (entry.applied && entry.change.after() != null) {
                    Files.deleteIfExists(entry.change.target());
                }
                if (entry.backup != null && Files.exists(entry.backup)) {
                    Files.move(entry.backup, entry.change.target(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    entry.backup = null;
                }
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        // 3. Clean up created directories in reverse order (bottom-up: child before parent)
        for (int i = createdDirectories.size() - 1; i >= 0; i--) {
            Path dir = createdDirectories.get(i);
            try {
                Files.delete(dir);
            } catch (NoSuchFileException ignored) {
                // Already deleted or absent
            } catch (DirectoryNotEmptyException ignored) {
                // Preserved because it contains non-empty contents
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        return first;
    }

    private ToolExecutionOutcome outcome(List<PreparedChange> changes) {
        ObjectNode json = ToolArguments.MAPPER.createObjectNode();
        json.put("changedFiles", changes.size());
        ArrayNode files = json.putArray("files");
        for (PreparedChange change : changes) {
            ObjectNode file = files.addObject();
            file.put("path", change.target().toString());
            file.put("operation", change.operation().name().toLowerCase());
            file.put("additions", change.additions());
            file.put("deletions", change.deletions());
        }
        return ToolExecutionOutcome.succeeded(
                new ToolOutput("Patched " + changes.size() + " file"
                        + (changes.size() == 1 ? "" : "s") + ".", List.of(), json),
                ResultStatus.AVAILABLE);
    }

    // ------------------------------------------------------------------------- parser

    private static List<PatchChange> parse(String patch) {
        List<String> lines = new ArrayList<>(List.of(
                patch.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int index = 0;
        if (index < lines.size() && "*** Begin Patch".equals(lines.get(index))) {
            index++;
        }
        List<PatchChange> changes = new ArrayList<>();
        while (index < lines.size()) {
            String line = lines.get(index);
            if ("*** End Patch".equals(line)) {
                index++;
                break;
            }
            Header header = header(line);
            index++;
            List<String> body = new ArrayList<>();
            while (index < lines.size()
                    && !isHeader(lines.get(index))
                    && !"*** End Patch".equals(lines.get(index))) {
                body.add(lines.get(index++));
            }
            changes.add(parseChange(header, body));
        }
        if (index != lines.size()) {
            throw invalid("content after *** End Patch");
        }
        if (changes.isEmpty()) {
            throw invalid("patch must contain at least one file section");
        }
        return List.copyOf(changes);
    }

    private static PatchChange parseChange(Header header, List<String> body) {
        return switch (header.operation()) {
            case ADD -> {
                StringBuilder content = new StringBuilder();
                for (String line : body) {
                    if (!line.startsWith("+")) {
                        throw invalid("add-file lines must start with '+': " + header.path());
                    }
                    content.append(line.substring(1)).append('\n');
                }
                yield new PatchChange(Operation.ADD, header.path(), List.of(), content.toString());
            }
            case DELETE -> {
                if (!body.isEmpty()) {
                    throw invalid("delete-file section must not contain hunks: " + header.path());
                }
                yield new PatchChange(Operation.DELETE, header.path(), List.of(), null);
            }
            case UPDATE -> new PatchChange(
                    Operation.UPDATE, header.path(), parseHunks(header.path(), body), null);
        };
    }

    private static List<Hunk> parseHunks(String path, List<String> body) {
        List<Hunk> hunks = new ArrayList<>();
        List<String> oldLines = null;
        List<String> newLines = null;
        int additions = 0;
        int deletions = 0;
        for (String line : body) {
            if (line.startsWith("@@")) {
                if (oldLines != null) {
                    hunks.add(hunk(path, oldLines, newLines, additions, deletions));
                }
                oldLines = new ArrayList<>();
                newLines = new ArrayList<>();
                additions = 0;
                deletions = 0;
                continue;
            }
            if (oldLines == null || line.isEmpty()) {
                throw invalid("update content must be inside an @@ hunk and carry a prefix: " + path);
            }
            switch (line.charAt(0)) {
                case ' ' -> {
                    oldLines.add(line.substring(1));
                    newLines.add(line.substring(1));
                }
                case '-' -> {
                    oldLines.add(line.substring(1));
                    deletions++;
                }
                case '+' -> {
                    newLines.add(line.substring(1));
                    additions++;
                }
                default -> throw invalid("hunk lines must start with space, '+' or '-': " + path);
            }
        }
        if (oldLines != null) {
            hunks.add(hunk(path, oldLines, newLines, additions, deletions));
        }
        if (hunks.isEmpty()) {
            throw invalid("update-file section must contain at least one @@ hunk: " + path);
        }
        return List.copyOf(hunks);
    }

    private static Hunk hunk(String path, List<String> oldLines, List<String> newLines,
                             int additions, int deletions) {
        if (oldLines.isEmpty()) {
            throw invalid("update hunk needs context or a removed line: " + path);
        }
        if (additions == 0 && deletions == 0) {
            throw invalid("update hunk contains no change: " + path);
        }
        return new Hunk(List.copyOf(oldLines), List.copyOf(newLines), additions, deletions);
    }

    private static Header header(String line) {
        for (Operation operation : Operation.values()) {
            String prefix = "*** " + operation.label + " File: ";
            if (line.startsWith(prefix)) {
                String path = line.substring(prefix.length()).trim();
                if (path.isEmpty()) {
                    throw invalid("file path is required");
                }
                return new Header(operation, path);
            }
        }
        throw invalid("expected a file section, got: " + line);
    }

    private static boolean isHeader(String line) {
        return line.startsWith("*** Update File: ")
                || line.startsWith("*** Add File: ")
                || line.startsWith("*** Delete File: ");
    }

    private static int uniqueIndexOf(List<String> lines, List<String> needle, Path target) {
        int found = -1;
        for (int i = 0; i <= lines.size() - needle.size(); i++) {
            if (lines.subList(i, i + needle.size()).equals(needle)) {
                if (found >= 0) {
                    throw new ToolExecutionException(TOOL_NAME,
                            "hunk matches more than once; add context: " + target);
                }
                found = i;
            }
        }
        if (found < 0) {
            throw new ToolExecutionException(TOOL_NAME,
                    "hunk context was not found; re-read the file: " + target);
        }
        return found;
    }

    private static int lineCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String normalized = normalizeLf(content);
        int count = normalized.split("\\n", -1).length;
        return normalized.endsWith("\n") ? count - 1 : count;
    }

    private static String normalizeLf(String text) {
        return text.replace("\r\n", "\n");
    }

    private static ToolExecutionException invalid(String message) {
        return new ToolExecutionException(TOOL_NAME, "invalid patch: " + message);
    }

    private static void carryOverPermissions(Path target, Path staged) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
            Files.setPosixFilePermissions(staged, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or a filesystem without POSIX permissions.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The operation failure or successful commit is more useful than temp cleanup noise.
        }
    }

    private enum Operation {
        UPDATE("Update"), ADD("Add"), DELETE("Delete");

        private final String label;

        Operation(String label) {
            this.label = label;
        }
    }

    private record Header(Operation operation, String path) {
    }

    private record Hunk(List<String> oldLines, List<String> newLines,
                        int additions, int deletions) {
    }

    private record PatchChange(Operation operation, String path,
                               List<Hunk> hunks, String addedContent) {
    }

    private record PreparedChange(Operation operation, Path target, String before, String after,
                                  int additions, int deletions) {
    }

    private static final class CommitEntry {
        private final PreparedChange change;
        private Path staged;
        private Path backup;
        private boolean applied;

        private CommitEntry(PreparedChange change, Path staged) {
            this.change = change;
            this.staged = staged;
        }
    }
}
