package com.harness.tool.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ResultStatus;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolOutput;
import com.harness.core.model.ToolSpec;
import com.harness.tool.ArgumentAwareConfirmationTool;
import com.harness.tool.Tool;
import com.harness.tool.filesystem.FileSystemAccessPolicy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Run a diagnostic command and return its output.
 *
 * <p><b>No shell is involved, ever.</b> The caller supplies the executable and its arguments as
 * separate fields and they are handed to {@link ProcessBuilder} as a list. That is what makes the
 * usual injection surface absent rather than filtered: {@code ;}, {@code |}, {@code >},
 * {@code $(...)} and backticks are not syntax here, they are just characters inside an argument
 * that the target program will reject or ignore. A shell executable is refused by name in
 * {@link CommandPolicy}, so a caller cannot reintroduce one.
 *
 * <p>The consequence is deliberate and worth stating: {@code shell} cannot modify files. There is no
 * redirection and no interpreter, so {@code echo x > /etc/nginx/nginx.conf} has nowhere to go.
 * Changing a file remains the job of {@code edit} / {@code write}, which keep their path policy.
 *
 * <p>{@code docker inspect} prints container environment variables, so every byte of output passes
 * through {@link ShellOutputSanitizer} before it reaches a ToolOutput.
 */
public final class ShellTool implements Tool, ArgumentAwareConfirmationTool {

    public static final String TOOL_NAME = "shell";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean WINDOWS = File.separatorChar == '\\';

    private final CommandPolicy commandPolicy;
    private final FileSystemAccessPolicy accessPolicy;
    private final Path defaultWorkingDirectory;
    private final int timeoutSeconds;
    private final int maxOutputBytes;

    public ShellTool(CommandPolicy commandPolicy, FileSystemAccessPolicy accessPolicy,
                     Path defaultWorkingDirectory) {
        this.commandPolicy = commandPolicy;
        this.accessPolicy = accessPolicy;
        this.defaultWorkingDirectory = defaultWorkingDirectory;
        EnvConfig config = EnvConfig.get();
        this.timeoutSeconds = config.getInt(EnvKey.SHELL_TIMEOUT_SECONDS, 60);
        this.maxOutputBytes = config.getInt(EnvKey.SHELL_MAX_OUTPUT_BYTES, 262_144);
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        stringProperty(schema, "command",
                "Executable name only, resolved from PATH — no path separators, no shell. "
                        + "Example: 'docker'.");
        ObjectNode args = schema.withObject("/properties").putObject("args");
        args.put("type", "array");
        args.put("description",
                "Arguments, one JSON array element each. Passed directly, never through a shell, "
                        + "so pipes, redirection and && are not operators here. "
                        + "Example: [\"logs\", \"redis\"].");
        args.putObject("items").put("type", "string");
        stringProperty(schema, "cwd",
                "Working directory. Absolute, or relative to the workspace root. "
                        + "Defaults to " + defaultWorkingDirectory + ".");
        schema.putArray("required").add("command");
        return new ToolSpec(
                TOOL_NAME,
                "Run a diagnostic command (Docker, Git, build tools) and return its stdout and "
                        + "stderr. There is no shell: pass the executable and arguments separately. "
                        + "Read-only diagnostics run immediately; anything else — including commands "
                        + "nobody has listed — asks the operator to approve it first. Shells and "
                        + "executable paths are refused outright. Output is capped at "
                        + maxOutputBytes + " bytes and runs time out after " + timeoutSeconds + "s.",
                schema,
                ToolCapability.MUTATION);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public boolean requiresConfirmation(JsonNode arguments) {
        // Tolerant on purpose: malformed arguments are reported by execute, not here.
        try {
            CommandPolicy.Verdict verdict = commandPolicy.decide(
                    text(arguments, "command"), stringArray(arguments, "args"));
            return verdict.needsConfirmation();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The reason travels with the summary on purpose: "a known state-changing command" and "a
     * command nobody anticipated" call for different levels of attention, and the operator cannot
     * tell them apart from the command line alone.
     */
    @Override
    public String confirmationSummary(JsonNode arguments) {
        String command = text(arguments, "command");
        List<String> args = stringArray(arguments, "args");
        String reason;
        try {
            reason = commandPolicy.decide(command, args).reason();
        } catch (RuntimeException e) {
            reason = "could not be evaluated";
        }
        return "Run: " + render(command, args) + " — " + reason + ".";
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String command = text(arguments, "command");
        if (command == null || command.isBlank()) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: command");
        }
        List<String> args = stringArray(arguments, "args");

        // Re-checked here, not only in requiresConfirmation: a refusal must hold even if the caller
        // reached execute by some path that skipped the confirmation step.
        CommandPolicy.Verdict verdict = commandPolicy.decide(command, args);
        if (verdict.denied()) {
            throw new ToolExecutionException(TOOL_NAME, verdict.reason());
        }
        List<String> effectiveArgs = commandPolicy.applyDefaults(command, args);

        Path workingDirectory = resolveWorkingDirectory(arguments);
        ProcessResult result = run(command, effectiveArgs, workingDirectory);
        String body = result.stdout() + result.stderr();

        StringBuilder text = new StringBuilder();
        text.append("$ ").append(render(command, effectiveArgs)).append('\n');
        text.append("cwd: ").append(workingDirectory).append('\n');
        text.append("exit: ").append(result.exitCode()).append('\n');
        if (!effectiveArgs.equals(args)) {
            text.append("(arguments were bounded by policy before running)\n");
        }
        if (result.truncated()) {
            text.append("(output truncated at ").append(maxOutputBytes).append(" bytes)\n");
        }
        if (result.timedOut()) {
            text.append("(timed out after ").append(timeoutSeconds).append("s and was killed)\n");
        }
        text.append(body.isBlank() ? "(no output)" : body);

        // The echoed command line is sanitised too, not just the streams. Arguments are where a
        // credential is most likely to appear — `mysql -p<password>` — and that copy would otherwise
        // ride out in the header untouched.
        String sanitized = ShellOutputSanitizer.sanitize(command, effectiveArgs, text.toString());

        // A non-zero exit is not a tool failure — the command ran and said so — so the status only
        // distinguishes "produced output" from "said nothing".
        return ToolExecutionOutcome.succeeded(
                ToolOutput.text(sanitized),
                body.isBlank() ? ResultStatus.EMPTY : ResultStatus.AVAILABLE);
    }

    private Path resolveWorkingDirectory(JsonNode arguments) {
        String cwd = text(arguments, "cwd");
        Path directory = cwd == null
                ? defaultWorkingDirectory
                : accessPolicy.resolveReadable(TOOL_NAME, Path.of(cwd));
        if (!Files.isDirectory(directory)) {
            throw new ToolExecutionException(TOOL_NAME, "cwd is not a directory: " + directory);
        }
        return directory;
    }

    // ────────────────────────────────────────────────────────────────── process plumbing

    private record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean timedOut, boolean truncated) {
    }

    private ProcessResult run(String command, List<String> args, Path workingDirectory) {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workingDirectory.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ToolExecutionException(TOOL_NAME,
                    "cannot start " + command + ": " + e.getMessage() + binaryHint(command), e);
        }

        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    timedOut.set(true);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shell-timeout");
        killer.setDaemon(true);
        killer.start();

        // Drained on separate threads: a child that fills one pipe while we read the other would
        // deadlock, and a chatty command fills a pipe quickly.
        BoundedStream stdout = new BoundedStream(maxOutputBytes);
        BoundedStream stderr = new BoundedStream(maxOutputBytes);
        Thread outReader = new Thread(() -> stdout.consume(process.getInputStream()), "shell-stdout");
        Thread errReader = new Thread(() -> stderr.consume(process.getErrorStream()), "shell-stderr");
        outReader.setDaemon(true);
        errReader.setDaemon(true);
        outReader.start();
        errReader.start();

        int exitCode;
        try {
            exitCode = process.waitFor();
            outReader.join(TimeUnit.SECONDS.toMillis(5));
            errReader.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ToolExecutionException(TOOL_NAME, "interrupted while waiting for " + command, e);
        }
        return new ProcessResult(exitCode, stdout.text(), stderr.text(),
                timedOut.get(), stdout.truncated() || stderr.truncated());
    }

    /**
     * On Windows, {@code mvn} and {@code npm} are {@code .cmd} scripts, which only run under
     * {@code cmd.exe}. This tool will not invoke one, so the failure needs to say why rather than
     * surface a bare "cannot run program".
     */
    private static String binaryHint(String command) {
        if (!WINDOWS || command.contains(".")) {
            return "";
        }
        for (String extension : List.of(".cmd", ".bat")) {
            if (findOnPath(command + extension) != null) {
                return " ('" + command + "' is a " + extension + " script, which needs cmd.exe; "
                        + "this tool deliberately does not invoke a second shell. Run it from a "
                        + "Linux deployment, or use the tool that performs the task directly.)";
            }
        }
        return "";
    }

    private static String findOnPath(String fileName) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** Readers that stop storing past a ceiling instead of letting a runaway command fill the heap. */
    private static final class BoundedStream {

        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        BoundedStream(int limit) {
            this.limit = limit;
        }

        void consume(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    if (text.length() < limit) {
                        text.append(buffer, 0, Math.min(read, limit - text.length()));
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                // The process died or was killed; its exit code is the real signal.
            }
        }

        String text() {
            return text.toString();
        }

        boolean truncated() {
            return truncated;
        }
    }

    // ────────────────────────────────────────────────────────────────── arguments

    private static String render(String command, List<String> args) {
        StringBuilder line = new StringBuilder(command);
        for (String arg : args) {
            line.append(' ').append(arg.indexOf(' ') >= 0 ? '"' + arg + '"' : arg);
        }
        return line.toString();
    }

    private static String text(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static List<String> stringArray(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new ToolExecutionException(TOOL_NAME,
                    name + " must be an array of strings, one element per argument");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new ToolExecutionException(TOOL_NAME, name + " must contain only strings");
            }
            items.add(item.asText());
        }
        return items;
    }

    private static void stringProperty(ObjectNode schema, String name, String description) {
        schema.withObject("/properties").putObject(name)
                .put("type", "string").put("description", description);
    }
}
