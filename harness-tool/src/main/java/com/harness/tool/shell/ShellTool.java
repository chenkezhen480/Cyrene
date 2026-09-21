package com.harness.tool.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Run a diagnostic command in the system shell and return its output.
 *
 * <p>Commands execute under the system shell ({@code cmd.exe /c} on Windows, {@code /bin/sh -c} on Linux)
 * to support native pipelines such as {@code | grep} or {@code | findstr}, allowing the agent to filter
 * large command output upfront and avoid high token consumption.
 *
 * <p>Security and integrity invariants are strictly enforced by {@link CommandPolicy}:
 * <ul>
 *   <li>File redirection ({@code >}, {@code >>}, {@code <}) is refused outright.</li>
 *   <li>File-writing pipeline commands ({@code tee}, {@code out-file}, etc.) are refused outright.</li>
 *   <li>Subshell interpreters ({@code bash}, {@code sh}, etc.) are refused outright.</li>
 *   <li>Only read-only diagnostic utilities run immediately; unlisted commands require operator confirmation.</li>
 * </ul>
 *
 * <p>Output is capped at {@code maxOutputBytes} (default 32KB) and execution times out after {@code timeoutSeconds}.
 * Every byte of output passes through {@link ShellOutputSanitizer} to prevent secret leakage.
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
        this.maxOutputBytes = config.getInt(EnvKey.SHELL_MAX_OUTPUT_BYTES, 32_768);
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = MAPPER.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        stringProperty(schema, "command",
                "The shell command to execute. Supports native shell pipelines (e.g. 'netstat -ano | findstr 3306' "
                        + "on Windows, or 'ps aux | grep java' on Linux). IMPORTANT: Always use pipes like grep/findstr, "
                        + "or limits like head/tail to filter outputs upfront and minimize tokens. "
                        + "Redirection to files (> and >>) and destructive commands are strictly forbidden.");
        ObjectNode args = schema.withObject("/properties").putObject("args");
        args.put("type", "array");
        args.put("description",
                "Optional arguments for the command. Can be omitted if the full command line is passed in 'command'. "
                        + "Example: [\"-ano\", \"|\", \"findstr\", \"3306\"].");
        args.putObject("items").put("type", "string");
        stringProperty(schema, "cwd",
                "Working directory. Absolute, or relative to the workspace root. "
                        + "Defaults to " + defaultWorkingDirectory + ".");
        schema.putArray("required").add("command");
        return new ToolSpec(
                TOOL_NAME,
                "Run a diagnostic shell command (Docker, Git, process/network tools) in the system shell and return stdout and stderr. "
                        + "Supports native shell pipelines (e.g. '| grep' or '| findstr'). You MUST filter verbose queries upfront using pipelines "
                        + "(e.g. 'netstat -ano | findstr <port>' or 'ps aux | grep <process>') to avoid massive token consumption and output truncation. "
                        + "File redirection (>, >>, <) and destructive commands are strictly forbidden. "
                        + "Read-only diagnostics run immediately; unlisted commands ask the operator to approve first. "
                        + "Output is capped at " + maxOutputBytes + " bytes and runs time out after " + timeoutSeconds + "s.",
                schema,
                ToolCapability.MUTATION);
    }

    @Override
    public String execute(JsonNode arguments) {
        return executeOutcome(arguments).content().modelContent();
    }

    @Override
    public boolean requiresConfirmation(JsonNode arguments) {
        try {
            String command = text(arguments, "command");
            List<String> args = stringArray(arguments, "args");
            CommandPolicy.Verdict verdict = commandPolicy.decide(command, args);
            return verdict.needsConfirmation();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String confirmationSummary(JsonNode arguments) {
        String command = text(arguments, "command");
        List<String> args = stringArray(arguments, "args");
        String commandLine = CommandPolicy.assembleCommandLine(command, args);
        String reason;
        try {
            reason = commandPolicy.decide(command, args).reason();
        } catch (RuntimeException e) {
            reason = "could not be evaluated";
        }
        return "Run: " + commandLine + " — " + reason + ".";
    }

    @Override
    public ToolExecutionOutcome executeOutcome(JsonNode arguments) {
        String command = text(arguments, "command");
        if (command == null || command.isBlank()) {
            throw new ToolExecutionException(TOOL_NAME, "Missing required parameter: command");
        }
        List<String> args = stringArray(arguments, "args");
        String commandLine = CommandPolicy.assembleCommandLine(command, args);

        CommandPolicy.Verdict verdict = commandPolicy.decide(command, args);
        if (verdict.denied()) {
            throw new ToolExecutionException(TOOL_NAME, verdict.reason());
        }
        String effectiveCommandLine = commandPolicy.applyDefaults(commandLine);

        Path workingDirectory = resolveWorkingDirectory(arguments);
        ProcessResult result = run(effectiveCommandLine, workingDirectory);
        String body = result.stdout() + result.stderr();

        StringBuilder text = new StringBuilder();
        text.append("$ ").append(effectiveCommandLine).append('\n');
        text.append("cwd: ").append(workingDirectory).append('\n');
        text.append("exit: ").append(result.exitCode()).append('\n');
        if (!effectiveCommandLine.equals(commandLine)) {
            text.append("(arguments were bounded by policy before running)\n");
        }
        if (result.truncated()) {
            text.append("(output truncated at ").append(maxOutputBytes)
                    .append(" bytes; use pipes like '| grep' or '| findstr' to query specific targets)\n");
        }
        if (result.timedOut()) {
            text.append("(timed out after ").append(timeoutSeconds).append("s and was killed)\n");
        }
        text.append(body.isBlank() ? "(no output)" : body);

        String sanitized = ShellOutputSanitizer.sanitize(command, args, text.toString());
        String sanitizedStdout = ShellOutputSanitizer.sanitize(command, args, result.stdout());
        String sanitizedStderr = ShellOutputSanitizer.sanitize(command, args, result.stderr());

        ObjectNode json = MAPPER.createObjectNode();
        ArrayNode commandJson = json.putArray("command");
        commandJson.add(command);
        for (String arg : args) {
            commandJson.add(ShellOutputSanitizer.sanitize(command, args, arg));
        }
        json.put("commandLine", effectiveCommandLine);
        json.put("cwd", workingDirectory.toString());
        json.put("exitCode", result.exitCode());
        json.put("timedOut", result.timedOut());
        json.put("truncated", result.truncated());
        json.put("stdout", sanitizedStdout);
        json.put("stderr", sanitizedStderr);

        return ToolExecutionOutcome.succeeded(
                new ToolOutput(sanitized, List.of(), json),
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

    private ProcessResult run(String commandLine, Path workingDirectory) {
        List<String> launcher;
        if (WINDOWS) {
            launcher = List.of("cmd.exe", "/c", commandLine);
        } else {
            launcher = List.of("/bin/sh", "-c", commandLine);
        }

        ProcessBuilder builder = new ProcessBuilder(launcher);
        builder.directory(workingDirectory.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ToolExecutionException(TOOL_NAME,
                    "cannot start " + commandLine + ": " + e.getMessage(), e);
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
            throw new ToolExecutionException(TOOL_NAME, "interrupted while waiting for " + commandLine, e);
        }
        return new ProcessResult(exitCode, stdout.text(), stderr.text(),
                timedOut.get(), stdout.truncated() || stderr.truncated());
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
