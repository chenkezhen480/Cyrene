package com.harness.tool.filesystem;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.ToolExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which filesystem paths a code tool may touch.
 *
 * <p><b>Invariant:</b> the {@link Path} returned by {@link #resolveReadable} / {@link #resolveWritable}
 * is the exact {@code Path} callers must hand to every subsequent NIO call. Never re-derive a path
 * from the raw argument once a resolve has succeeded, and never pass the raw argument to NIO. That
 * identity is the whole safety argument: it removes any gap between the string this class judged and
 * the string the operating system acts on.
 *
 * <p>Writable roots come from trusted server configuration only. {@code project-apis.json}'s
 * {@code projectRoot} is never a write source.
 *
 * <p>ponytail: resolve-then-act, so there is a TOCTOU window. This defends against a tool argument
 * that escapes the workspace, not against a hostile local process racing the agent — the dangerous
 * half is structural instead: {@code edit} ends in one rename of the directory entry and {@code write}
 * uses {@code CREATE_NEW}, so neither ever follows a link that appears mid-flight.
 * ponytail: hard links are not detectable portably. The write side is immune by construction, which
 * leaves only a read-side leak in confined mode. Documented, not fixed.
 */
public final class FileSystemAccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(FileSystemAccessPolicy.class);

    /** Where a read may land. */
    public enum ReadScope {
        /** Any path the process can read — needed to inspect Redis / MySQL / Nginx / Maven / SDK config. */
        HOST,
        /** Only the configured roots. */
        WORKSPACE
    }

    /** Numeric caps and process settings shared by the code tools. */
    public record Settings(
            ReadScope readScope,
            String rgPath,
            int rgTimeoutSeconds,
            int maxOutputBytes,
            int maxResults,
            int readMaxLines,
            long editMaxFileBytes
    ) {
        /**
         * {@code rgPath} is blank unless an operator sets it. Search never reaches for a binary the
         * deployment did not ask for: the NIO backend is the complete default, and ripgrep is an
         * opt-in accelerator.
         */
        public static Settings fromEnv() {
            EnvConfig config = EnvConfig.get();
            String scope = config.getString(EnvKey.CODE_READ_SCOPE, "host");
            return new Settings(
                    "workspace".equalsIgnoreCase(scope) ? ReadScope.WORKSPACE : ReadScope.HOST,
                    config.getString(EnvKey.CODE_RG_PATH, ""),
                    config.getInt(EnvKey.CODE_RG_TIMEOUT_SECONDS, 30),
                    config.getInt(EnvKey.CODE_MAX_OUTPUT_BYTES, 262144),
                    config.getInt(EnvKey.TOOL_MAX_RESULTS, 100),
                    config.getInt(EnvKey.CODE_READ_MAX_LINES, 2000),
                    config.getLong(EnvKey.CODE_EDIT_MAX_FILE_MB, 10) * 1024L * 1024L);
        }

        public static Settings defaults() {
            return new Settings(ReadScope.HOST, "", 30, 262_144, 100, 2000, 10L * 1024 * 1024);
        }
    }

    private final boolean readUnrestricted;
    private final List<Path> readableRoots;
    private final List<Path> writableRoots;
    private final Path searchRoot;
    private final Settings settings;
    private volatile FileSearchBackend searchBackend;

    private FileSystemAccessPolicy(boolean readUnrestricted, List<Path> readableRoots,
                                   List<Path> writableRoots, Path searchRoot, Settings settings) {
        this.readUnrestricted = readUnrestricted;
        this.readableRoots = List.copyOf(readableRoots);
        this.writableRoots = List.copyOf(writableRoots);
        this.searchRoot = searchRoot;
        this.settings = settings;
    }

    /**
     * Normal session: reads follow {@code HARNESS_CODE_READ_SCOPE}, writes land only inside the
     * agent's own source root or a configured backend root.
     */
    public static FileSystemAccessPolicy host(String agentRoot, List<String> backendRoots,
                                              Settings settings) {
        String agent = agentRoot == null || agentRoot.isBlank()
                ? Path.of("").toAbsolutePath().toString()
                : agentRoot;
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(canonicalRoot("agent root", agent));
        List<String> backends = backendRoots == null ? List.of() : backendRoots;
        for (String backendRoot : backends) {
            roots.add(canonicalRoot("backend root", backendRoot));
        }
        List<Path> writable = List.copyOf(roots);
        // Search defaults to the backend being worked on; with no backend configured, to the Agent's
        // own source. A model that omits `path` should land where the work is.
        Path search = backends.isEmpty()
                ? writable.get(0)
                : canonicalRoot("backend root", backends.get(0));
        return new FileSystemAccessPolicy(settings.readScope() == ReadScope.HOST,
                writable, writable, search, settings);
    }

    /**
     * Discovery scan: both reads and writes are limited to one root, and writes are refused
     * outright. Callers should also simply not register {@code edit} / {@code write}.
     */
    public static FileSystemAccessPolicy confined(String root, Settings settings) {
        Path only = canonicalRoot("workspace root", root);
        return new FileSystemAccessPolicy(false, List.of(only), List.of(), only, settings);
    }

    /** Default directory for a tool call that omits its {@code path} argument. */
    public Path searchRoot() {
        return searchRoot;
    }

    public boolean writesEnabled() {
        return !writableRoots.isEmpty();
    }

    public Settings settings() {
        return settings;
    }

    /**
     * The configured search backend, resolved once and cached.
     *
     * <p>Plain NIO unless {@code HARNESS_CODE_RG_PATH} names a ripgrep binary that actually runs —
     * search never hunts for one on its own. A configured-but-unusable path falls back to NIO and
     * says so, because silently answering from a slower backend would leave an operator believing
     * the accelerator was on.
     */
    public FileSearchBackend searchBackend() {
        FileSearchBackend backend = searchBackend;
        if (backend == null) {
            synchronized (this) {
                backend = searchBackend;
                if (backend == null) {
                    String rgPath = settings.rgPath();
                    if (rgPath == null || rgPath.isBlank()) {
                        backend = new NioSearchBackend();
                    } else if (RipgrepSearchBackend.isAvailable(rgPath)) {
                        backend = new RipgrepSearchBackend(settings);
                    } else {
                        log.warn("[CodeTools] {} names '{}', which is not runnable; "
                                        + "falling back to the NIO search backend",
                                EnvKey.CODE_RG_PATH, rgPath);
                        backend = new NioSearchBackend();
                    }
                    searchBackend = backend;
                }
            }
        }
        return backend;
    }

    /** Resolve a path for reading. The returned path is real, absolute, and link-free. */
    public Path resolveReadable(String toolName, Path requested) {
        Path path = requireAbsolute(toolName, requested);
        Path real = toReal(toolName, requested, path);
        if (!readUnrestricted && !withinAny(real, readableRoots)) {
            throw new ToolExecutionException(toolName,
                    "path is outside the readable roots (" + describe(readableRoots) + "): " + requested);
        }
        return real;
    }

    /**
     * Resolve a path for creating or replacing a file.
     *
     * <p>Only the <em>parent</em> is realpath-resolved, then the plain file name is appended. Doing it
     * in that order matters on Windows: {@code toRealPath} collapses {@code ..} textually before it
     * touches the filesystem, so a whole-path resolve would reintroduce the classic
     * collapse-then-compare bypass. Refusing every {@code .} / {@code ..} component up front means
     * that collapse can never disagree with the kernel here.
     */
    public Path resolveWritable(String toolName, Path requested) {
        if (writableRoots.isEmpty()) {
            throw new ToolExecutionException(toolName, "file writes are disabled in this scope");
        }
        Path path = requireAbsolute(toolName, requested);
        rejectDotComponents(toolName, path);

        Path parent = path.getParent();
        if (parent == null) {
            throw new ToolExecutionException(toolName, "path does not name a file: " + requested);
        }
        String name = path.getFileName().toString();
        if (name.equals(".") || name.equals("..")) {
            throw new ToolExecutionException(toolName, "path does not name a file: " + requested);
        }
        Path realParent = toReal(toolName, requested, parent);
        if (!withinAny(realParent, writableRoots)) {
            throw new ToolExecutionException(toolName,
                    "path is outside the writable roots (" + describe(writableRoots) + "): " + requested);
        }
        return realParent.resolve(name);
    }

    /**
     * Refuse anything that is not a plain, existing, non-link regular file.
     *
     * <p>{@code Files.isRegularFile} is doing four jobs at once here, which is why it is preferred
     * over a positive "is this a fine file" test: it follows links, so directories, junctions,
     * dangling symlinks and cloud placeholder files all come back false in one condition.
     * {@code Files.isSymbolicLink} alone would not do — on Windows it is false for junctions, which
     * carry a different reparse tag.
     */
    public void requireReplaceableRegularFile(String toolName, Path target) {
        if (!Files.isRegularFile(target)) {
            throw new ToolExecutionException(toolName, "not an existing regular file: " + target);
        }
        if (Files.isSymbolicLink(target)) {
            throw new ToolExecutionException(
                    toolName, "refusing to edit through a symbolic link: " + target);
        }
    }

    // ────────────────────────────────────────────────────────────────── helpers

    private static Path requireAbsolute(String toolName, Path requested) {
        if (requested == null) {
            throw new ToolExecutionException(toolName, "path is required");
        }
        if (!requested.isAbsolute()) {
            throw new ToolExecutionException(toolName, "path must be absolute: " + requested);
        }
        return requested;
    }

    private static Path toReal(String toolName, Path requested, Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new ToolExecutionException(
                    toolName, "cannot resolve path " + requested + ": " + e.getMessage(), e);
        }
    }

    private static void rejectDotComponents(String toolName, Path path) {
        for (Path component : path) {
            String name = component.toString();
            if (name.equals(".") || name.equals("..")) {
                throw new ToolExecutionException(toolName,
                        "path must not contain '.' or '..' components: " + path);
            }
        }
    }

    /**
     * {@code Path.startsWith} is already case-insensitive on Windows (element-wise
     * {@code equalsIgnoreCase}) and case-sensitive on Unix in the same way the filesystem is, so it
     * is the right comparison as-is. Do not hand-roll case folding here.
     */
    private static boolean withinAny(Path real, List<Path> roots) {
        for (Path root : roots) {
            if (real.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<Path> roots) {
        if (roots.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        for (Path root : roots) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(root);
        }
        return text.toString();
    }

    /**
     * A root is canonicalized once at construction. A root that does not exist yet degrades to a
     * normalized absolute path and is then inert: no writable target can have its parent under a
     * directory that does not exist, so nothing can ever match it.
     */
    private static Path canonicalRoot(String what, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(what + " is not a valid path: " + raw, e);
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(what + " must be an absolute path: " + raw);
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
