package com.harness.tool.filesystem;

import com.harness.core.exception.ToolExecutionException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure-Java fallback for when ripgrep is not installed.
 *
 * <p>Deliberately mirrors {@link RipgrepSearchBackend}'s behaviour: hidden files are searched,
 * gitignore files are not consulted, and the same build directories are skipped. A caller must not
 * be able to tell which backend answered.
 */
final class NioSearchBackend implements FileSearchBackend {

    /** Directory names skipped by both backends. */
    static final Set<String> SKIP_DIRS = Set.of(".git", "target", "node_modules", "build", "dist");

    /** A file this large is not source code; reading it whole to scan it is not worth the memory. */
    private static final long MAX_SCAN_BYTES = 32L * 1024 * 1024;
    private static final int BINARY_SNIFF_BYTES = 8192;

    @Override
    public String name() {
        return "nio";
    }

    @Override
    public Page glob(Path root, String pattern, String afterRelative, int limit) {
        PathMatcher matcher = compileGlob(pattern);
        // Root-level files do not match a "**/"-prefixed pattern in Java's glob syntax, but they do
        // in ripgrep's. Strip the prefix once so both backends agree.
        PathMatcher fallback = pattern.startsWith("**/")
                ? compileGlob(pattern.substring(3))
                : null;

        PageCollector collector = new PageCollector(afterRelative, limit);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return skipDirectory(dir, attrs) ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isLinkOrOther(attrs) && matches(matcher, fallback, root, file)) {
                        collector.offer(relativeKey(root, file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolExecutionException(
                    "glob", "failed to walk " + root + ": " + e.getMessage(), e);
        }
        return page(root, collector);
    }

    /** Path relative to the search root, always {@code /} separated so paging is portable. */
    static String relativeKey(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static Page page(Path root, PageCollector collector) {
        List<Path> paths = new ArrayList<>();
        for (String key : collector.ascending()) {
            paths.add(root.resolve(key));
        }
        return new Page(paths, collector.hasMore());
    }

    @Override
    public GrepResult grep(Path root, String pattern, String fileGlob,
                           int contextLines, int maxResults, int maxOutputBytes) {
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new ToolExecutionException("grep", "invalid regex: " + e.getMessage(), e);
        }
        PathMatcher fileMatcher = fileGlob == null || fileGlob.isBlank()
                ? null
                : compileGlob(fileGlob);
        PathMatcher fileFallback = fileGlob != null && fileGlob.startsWith("**/")
                ? compileGlob(fileGlob.substring(3))
                : null;

        List<GrepFileResult> results = new ArrayList<>();
        Budget budget = new Budget(maxResults, maxOutputBytes);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return skipDirectory(dir, attrs) ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isLinkOrOther(attrs)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (fileMatcher != null
                            && !matches(fileMatcher, fileFallback, root, file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    List<GrepLine> lines = scanFile(file, regex, contextLines, budget);
                    if (!lines.isEmpty()) {
                        results.add(new GrepFileResult(file.toAbsolutePath(), lines));
                    }
                    return budget.exhausted() ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolExecutionException(
                    "grep", "failed to walk " + root + ": " + e.getMessage(), e);
        }
        results.sort(Comparator.comparing(result -> result.path().toString()));
        return new GrepResult(results, budget.truncated());
    }

    private List<GrepLine> scanFile(Path file, Pattern regex, int contextLines, Budget budget) {
        List<String> lines;
        try {
            if (Files.size(file) > MAX_SCAN_BYTES || isBinary(file)) {
                return List.of();
            }
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            // Unreadable or vanished mid-walk: skip it rather than failing the whole search.
            return List.of();
        }

        List<GrepLine> matches = new ArrayList<>();
        for (int i = 0; i < lines.size() && !budget.exhausted(); i++) {
            if (!regex.matcher(lines.get(i)).find()) {
                continue;
            }
            int from = Math.max(0, i - contextLines);
            int to = Math.min(lines.size(), i + contextLines + 1);
            List<String> before = List.copyOf(lines.subList(from, i));
            List<String> after = List.copyOf(lines.subList(i + 1, to));
            int chars = lines.get(i).length() + 1;
            for (String line : before) {
                chars += line.length() + 1;
            }
            for (String line : after) {
                chars += line.length() + 1;
            }
            if (!budget.add(true, chars)) {
                break;
            }
            matches.add(new GrepLine(i + 1, lines.get(i), before, after));
        }
        return matches;
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
            return true;
        }
        return false;
    }

    private static boolean skipDirectory(Path dir, BasicFileAttributes attrs) {
        if (isLinkOrOther(attrs)) {
            return true;
        }
        Path name = dir.getFileName();
        return name != null && SKIP_DIRS.contains(name.toString());
    }

    /**
     * Junctions are the reason this exists: on Windows {@code BasicFileAttributes.isSymbolicLink()}
     * is false for them (they carry a different reparse tag), so without the {@code isOther()} half
     * a walk would happily descend into a junction pointing anywhere on the machine.
     */
    private static boolean isLinkOrOther(BasicFileAttributes attrs) {
        return attrs.isSymbolicLink() || attrs.isOther();
    }

    private static boolean matches(PathMatcher matcher, PathMatcher fallback, Path root, Path file) {
        Path relative = root.relativize(file);
        return matcher.matches(relative)
                || matcher.matches(file.getFileName())
                || (fallback != null && fallback.matches(file.getFileName()));
    }

    private static PathMatcher compileGlob(String pattern) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException e) {
            throw new ToolExecutionException("glob", "invalid glob pattern: " + pattern, e);
        }
    }
}
