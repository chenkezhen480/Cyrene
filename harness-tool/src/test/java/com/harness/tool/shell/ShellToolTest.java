package com.harness.tool.shell;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolExecutionOutcome;
import com.harness.core.model.ToolSpec;
import com.harness.tool.filesystem.FileSystemAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShellToolTest {

    @TempDir
    Path root;

    private ShellTool tool;

    @BeforeEach
    void setUp() {
        FileSystemAccessPolicy access = FileSystemAccessPolicy.host(
                root.toString(), List.of(), FileSystemAccessPolicy.Settings.defaults());
        tool = new ShellTool(CommandPolicy.defaults(), access, root.toAbsolutePath());
    }

    private static ObjectNode args(String command, String... arguments) {
        ObjectNode node = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        node.put("command", command);
        ArrayNode array = node.putArray("args");
        for (String argument : arguments) {
            array.add(argument);
        }
        return node;
    }

    /**
     * Exercises the real process plumbing: exit code, stderr capture, and that arguments arrive
     * unmangled. {@code java -version} is on the default allow list and writes to stderr, so a
     * passing assertion proves both streams were drained.
     */
    @Test
    void runsAnAllowedCommandAndCapturesBothStreams() {
        ToolExecutionOutcome outcome = tool.executeOutcome(args("java", "-version"));
        String output = outcome.content().text();

        assertThat(output).contains("$ java -version").contains("exit: 0");
        assertThat(output).containsIgnoringCase("version");
        assertThat(outcome.content().json().path("command")).extracting(node -> node.asText())
                .containsExactly("java", "-version");
        assertThat(outcome.content().json().path("cwd").asText()).isEqualTo(root.toString());
        assertThat(outcome.content().json().path("exitCode").asInt()).isZero();
        assertThat(outcome.content().json().path("timedOut").asBoolean()).isFalse();
        assertThat(outcome.content().json().path("truncated").asBoolean()).isFalse();
        assertThat(outcome.content().json().path("stderr").asText())
                .containsIgnoringCase("version");
    }

    /** Structural refusals never reach the operator: reading them carefully would not make them safe. */
    @Test
    void refusesStructuralViolationsWithoutStartingAnything() {
        assertThatThrownBy(() -> tool.execute(args("bash", "-c", "rm -rf /")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("is a shell");
        assertThatThrownBy(() -> tool.execute(args("/bin/rm", "-rf", "/")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("bare executable name");
        assertThatThrownBy(() -> tool.execute(args("docker", "logs", "-f", "redis")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("never returns");
    }

    @Test
    void asksTheOperatorForAnythingItWouldNotRunImmediately() {
        // Known state-changing commands, and equally the ones nobody listed.
        assertThat(tool.requiresConfirmation(args("docker", "restart", "redis"))).isTrue();
        assertThat(tool.requiresConfirmation(args("docker", "compose", "up", "-d"))).isTrue();
        assertThat(tool.requiresConfirmation(args("mvn", "test"))).isTrue();
        assertThat(tool.requiresConfirmation(args("rm", "-rf", "/"))).isTrue();
        assertThat(tool.requiresConfirmation(args("curl", "http://example.com"))).isTrue();
        // Read-only diagnostics still run without a prompt.
        assertThat(tool.requiresConfirmation(args("docker", "ps"))).isFalse();
        assertThat(tool.requiresConfirmation(args("git", "status"))).isFalse();
    }

    /** The two kinds of ask must be worded differently, or the reviewer cannot calibrate. */
    @Test
    void distinguishesUnrecognisedCommandsFromKnownRiskyOnes() {
        assertThat(tool.confirmationSummary(args("docker", "restart", "redis")))
                .contains("docker restart redis")
                .contains("changes running state or executes project code");
        assertThat(tool.confirmationSummary(args("curl", "http://example.com")))
                .contains("not on the allow list — approve only if you recognise it");
    }

    /** The prompt shows the effective command line, so an injected --tail is not a hidden action. */
    @Test
    void announcesPolicyBoundedArgumentsInTheOutput() {
        String output = tool.execute(args("docker", "logs", "redis"));

        assertThat(output).contains("--tail 500").contains("bounded by policy");
    }

    @Test
    void rejectsMalformedArguments() {
        ObjectNode notAnArray = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        notAnArray.put("command", "docker");
        notAnArray.put("args", "ps");
        assertThatThrownBy(() -> tool.execute(notAnArray))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("must be an array of strings");

        ObjectNode noCommand = new ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        assertThatThrownBy(() -> tool.execute(noCommand))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("Missing required parameter: command");
    }

    @Test
    void refusesAWorkingDirectoryThatIsNotADirectory() throws IOException {
        Path file = Files.writeString(root.resolve("not-a-dir.txt"), "x");

        assertThatThrownBy(() -> tool.execute(args("java", "-version").put("cwd", file.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("cwd is not a directory");
    }

    @Test
    void reportsAMissingWorkingDirectoryThroughThePolicy() {
        assertThatThrownBy(() -> tool.execute(
                args("java", "-version").put("cwd", root.resolve("nope").toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("cannot resolve path");
    }

    @Test
    void declaresItselfAsAMutatingTool() {
        ToolSpec spec = tool.spec();

        assertThat(spec.name()).isEqualTo("shell");
        assertThat(spec.capability().name()).isEqualTo("MUTATION");
        assertThat(spec.requiresConfirmation()).isFalse();
        assertThat(spec.parameters().get("properties").has("command")).isTrue();
        assertThat(spec.parameters().get("properties").has("args")).isTrue();
    }

    @Test
    void executesPipelineWithPipesNatively() {
        String filterCmd = File.separatorChar == '\\' ? "findstr" : "grep";
        ObjectNode input = args("java", "-version", "2>&1", "|", filterCmd, "version");
        ToolExecutionOutcome outcome = tool.executeOutcome(input);
        String output = outcome.content().text();

        assertThat(output).containsIgnoringCase("version");
        assertThat(outcome.content().json().path("exitCode").asInt()).isZero();
        assertThat(outcome.content().json().path("commandLine").asText())
                .contains("java -version 2>&1 | " + filterCmd + " version");
    }

    @Test
    void refusesFileRedirectionOutright() {
        assertThatThrownBy(() -> tool.execute(args("echo", "test", ">", "file.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("file redirection");
        assertThatThrownBy(() -> tool.execute(args("docker", "ps", ">>", "file.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("file redirection");
    }

    @Test
    void refusesWritingCommandsInPipeline() {
        assertThatThrownBy(() -> tool.execute(args("docker", "ps", "|", "tee", "out.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("strictly prohibited");
    }

    @Test
    void runsNetstatDiagnosticsWithoutConfirmation() {
        assertThat(tool.requiresConfirmation(args("netstat", "-ano"))).isFalse();
        assertThat(tool.requiresConfirmation(args("netstat", "-ano", "|", "findstr", "3306"))).isFalse();
    }
}
