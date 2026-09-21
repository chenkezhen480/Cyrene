package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * ripgrep-backed search.
 *
 * <p>Arguments are handed to {@link ProcessBuilder} as a list — there is no shell and no string
 * concatenation, so a model-supplied pattern can never reach a command interpreter.
 *
 * <p>Flags mirror {@link NioSearchBackend} on purpose. {@code --hidden --no-ignore} means hidden and
 * gitignored files are searched (the point of these tools is to inspect real middleware config), and
 * the explicit {@code -g '!…'} list replaces the build directories both backends skip. Without this
 * alignment, whether ripgrep happened to be installed would silently change what the agent sees.
 */
final class RipgrepSearchBackend implements FileSearchBackend {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Search hidden files and do not honour gitignore/ignore files. */
    private static final List<String> BASE_FLAGS = List.of(
            "--hidden", "--no-ignore", "--no-config", "--color", "never");

    /** Long lines are truncated rather than streamed whole, bounding per-event memory. */
    private static final int MAX_COLUMNS = 1000;

    private final FileSystemAccessPolicy.Settings settings;

    RipgrepSearchBackend(FileSystemAccessPolicy.Settings settings) {
        this.settings = settings;
    }

    /** Probe whether this path actually runs. Anything other than a clean exit means "no". */
    static boolean isAvailable(String rgPath) {
        if (rgPath == null || rgPath.isBlank()) {
            return false;
        }
        ProcessBuilder builder = new ProcessBuilder(rgPath, "--version");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = null;
        try {
            process = builder.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    @Override
    public String name() {
        return "ripgrep";
    }

    @Override
    public Page glob(Path root, String pattern, String afterRelative, int limit) {
        List<String> command = new ArrayList<>();
        command.add(settings.rgPath());
        command.addAll(BASE_FLAGS);
        command.add("--files");
        command.add("-g");
        command.add(pattern);
        addSkipGlobs(command);
        command.add("--");
        command.add(".");

        PageCollector collector = new PageCollector(afterRelative, limit);
        // No early exit: a cursor page is the smallest N keys after a point, which is not knowable
        // until every candidate has been seen. The collector keeps this O(limit) in memory.
        Run run = run(command, root, "glob", line -> {
            if (!line.isBlank()) {
                String key = keyOf(line, root);
                if (key != null) {
                    collector.offer(key);
                }
            }
            return true;
        });
        run.rethrowIfFailed("glob", settings.rgTimeoutSeconds());
        return NioSearchBackend.page(root, collector);
    }

    /** Relative key for one rg output path, or null if it somehow sits outside the search root. */
    private static String keyOf(String text, Path root) {
        Path path = Path.of(text);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            return null;
        }
        return NioSearchBackend.relativeKey(root, path);
    }

    @Override
    public GrepResult grep(Path root, String pattern, String fileGlob,
                           int contextLines, int maxResults, int maxOutputBytes) {
        List<String> command = new ArrayList<>();
        command.add(settings.rgPath());
        command.addAll(BASE_FLAGS);
        command.add("--json");
        command.add("--max-columns");
        command.add(String.valueOf(MAX_COLUMNS));
        command.add("--max-columns-preview");
        if (contextLines > 0) {
            command.add("-C");
            command.add(String.valueOf(contextLines));
        }
        if (fileGlob != null && !fileGlob.isBlank()) {
            command.add("-g");
            command.add(fileGlob);
        }
        addSkipGlobs(command);
        command.add("-e");
        command.add(pattern);
        command.add("--");
        command.add(".");

        Map<Path, List<Event>> byFile = new LinkedHashMap<>();
        Budget budget = new Budget(maxResults, maxOutputBytes);
        // The JSON stream only repeats `path` when it changes, so the last one seen is the subject
        // of any event that omits it.
        Path[] current = {null};
        Run run = run(command, root, "grep", line -> {
            JsonNode event = parse(line);
            if (event == null) {
                return true;
            }
            String type = event.path("type").asText();
            if (!"match".equals(type) && !"context".equals(type) && !"begin".equals(type)) {
                return true;
            }
            JsonNode data = event.path("data");
            JsonNode pathNode = data.path("path").path("text");
            if (!pathNode.isMissingNode() && !pathNode.isNull()) {
                current[0] = resolve(pathNode.asText(), root);
            }
            if (current[0] == null || !data.hasNonNull("line_number")) {
                return true;
            }
            String text = stripLineBreak(data.path("lines").path("text").asText());
            boolean isMatch = "match".equals(type);
            if (!budget.add(isMatch, text.length())) {
                return false;
            }
            byFile.computeIfAbsent(current[0], key -> new ArrayList<>())
                    .add(new Event(data.path("line_number").asInt(), text, isMatch));
            return !budget.exhausted();
        });
        run.rethrowIfFailed("grep", settings.rgTimeoutSeconds());
        return new GrepResult(assemble(byFile, contextLines), budget.truncated());
    }

    /** Attach the surrounding context events to each match, then sort for a deterministic order. */
    private static List<GrepFileResult> assemble(Map<Path, List<Event>> byFile, int contextLines) {
        List<GrepFileResult> results = new ArrayList<>();
        for (Map.Entry<Path, List<Event>> entry : byFile.entrySet()) {
            List<Event> events = entry.getValue();
            List<FileSearchBackend.GrepLine> lines = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                if (!events.get(i).match()) {
                    continue;
                }
                List<String> before = new ArrayList<>();
                for (int j = i - 1; j >= 0 && before.size() < contextLines; j--) {
                    before.add(0, events.get(j).text());
                }
                List<String> after = new ArrayList<>();
                for (int j = i + 1; j < events.size() && after.size() < contextLines; j++) {
                    after.add(events.get(j).text());
                }
                lines.add(new FileSearchBackend.GrepLine(
                        events.get(i).lineNumber(), events.get(i).text(),
                        List.copyOf(before), List.copyOf(after)));
            }
            if (!lines.isEmpty()) {
                results.add(new FileSearchBackend.GrepFileResult(entry.getKey(), lines));
            }
        }
        results.sort(Comparator.comparing(result -> result.path().toString()));
        return results;
    }

    // ────────────────────────────────────────────────────────────────── process plumbing

    private record Run(int exitCode, boolean timedOut, String stderr) {

        /** Exit 1 is ripgrep's ordinary "no matches"; anything else non-zero is a real failure. */
        void rethrowIfFailed(String toolName, int timeoutSeconds) {
            if (timedOut) {
                throw new ToolExecutionException(toolName, "ripgrep timed out after "
                        + timeoutSeconds + "s; narrow the search path or pattern");
            }
            if (exitCode > 1) {
                throw new ToolExecutionException(toolName,
                        "ripgrep failed (exit " + exitCode + "): " + stderr.trim());
            }
        }
    }

    private Run run(List<String> command, Path root, String toolName, Predicate<String> onLine) {
        Process process;
        try {
            process = new ProcessBuilder(command).directory(root.toFile()).start();
        } catch (IOException e) {
            throw new ToolExecutionException(toolName,
                    "cannot start ripgrep (" + settings.rgPath() + "): " + e.getMessage(), e);
        }

        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(settings.rgTimeoutSeconds(), TimeUnit.SECONDS)) {
                    timedOut.set(true);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "rg-timeout");
        killer.setDaemon(true);
        killer.start();

        StringBuilder stderr = new StringBuilder();
        Thread drain = new Thread(() -> drain(process.getErrorStream(), stderr), "rg-stderr");
        drain.setDaemon(true);
        drain.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!onLine.test(line)) {
                    // Enough matches already. Killing beats reading a whole monorepo to discard it.
                    process.destroyForcibly();
                    break;
                }
            }
        } catch (IOException e) {
            // stdout closed early; the exit code below is the real signal
        }

        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ToolExecutionException(toolName, "interrupted while waiting for ripgrep", e);
        }
        return new Run(exit, timedOut.get(), stderr.toString());
    }

    private static void drain(InputStream stream, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.length() < 4096) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            // the process is gone; its exit code is what matters
        }
    }

    // ────────────────────────────────────────────────────────────────── helpers

    /** ripgrep applies the last matching glob, so exclusions must follow the caller's include glob. */
    private static void addSkipGlobs(List<String> command) {
        for (String directory : NioSearchBackend.SKIP_DIRS) {
            command.add("-g");
            command.add("!" + directory + "/**");
        }
    }

    private static JsonNode parse(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path resolve(String text, Path root) {
        Path path = Path.of(text);
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String stripLineBreak(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }

    private record Event(int lineNumber, String text, boolean match) {
    }
}
