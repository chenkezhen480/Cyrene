package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchToolsTest {

    /** Points at a binary that cannot exist, so these tests always exercise the NIO backend. */
    private static final FileSystemAccessPolicy.Settings NIO_ONLY =
            new FileSystemAccessPolicy.Settings(FileSystemAccessPolicy.ReadScope.HOST,
                    "rg-definitely-not-installed-xyz", 5, 65_536, 50, 500, 1024 * 1024);

    @TempDir
    Path root;

    private FileSystemAccessPolicy policy;
    private FileSystemWorkspace workspace;

    @BeforeEach
    void setUp() {
        policy = FileSystemAccessPolicy.host(root.toString(), List.of(), NIO_ONLY);
        workspace = FileSystemWorkspace.host(policy.searchRoot());
    }

    private GlobTool globTool() {
        return new GlobTool(policy, workspace);
    }

    private GrepTool grepTool() {
        return new GrepTool(policy, workspace);
    }

    private static ObjectNode globArgs(String pattern) {
        return ToolArguments.MAPPER.createObjectNode().put("pattern", pattern);
    }

    private static ObjectNode grepArgs(String pattern) {
        return ToolArguments.MAPPER.createObjectNode().put("pattern", pattern);
    }

    private void fixture() throws IOException {
        Files.writeString(root.resolve("App.java"), "class App {\n    void run() {\n        go();\n    }\n}\n");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("Util.java"), "class Util {\n}\n");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target").resolve("Generated.java"), "class App {}\n");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules").resolve("dep.java"), "class App {}\n");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "class App {}\n");
        Files.writeString(root.resolve(".env"), "REDIS_HOST=127.0.0.1\n");
    }

    @Test
    void globFindsMatchingFilesAsAbsolutePaths() throws IOException {
        fixture();

        String result = globTool().execute(globArgs("**/*.java"));

        assertThat(result).contains(root.resolve("App.java").toRealPath().toString());
        assertThat(result).contains(root.resolve("sub").resolve("Util.java").toRealPath().toString());
    }

    @Test
    void globSkipsBuildAndVcsDirectories() throws IOException {
        fixture();

        String result = globTool().execute(globArgs("**/*.java"));

        assertThat(result).doesNotContain("Generated.java");
        assertThat(result).doesNotContain("node_modules");
        assertThat(result).doesNotContain(".git");
    }

    /**
     * The old discovery tools hardcoded a blocklist for {@code .env} / {@code *.key} /
     * {@code application*.yml}. Inspecting real middleware configuration is the whole point now, and
     * {@code read} could open those files anyway — hiding the names was never a boundary.
     */
    @Test
    void globNoLongerHidesConfigurationFiles() throws IOException {
        fixture();

        assertThat(globTool().execute(globArgs("**/.env")))
                .contains(root.toRealPath().resolve(".env").toString());
    }

    @Test
    void globSearchesTheWorkspaceRootWhenNoPathIsGiven() throws IOException {
        Files.writeString(root.resolve("notes.txt"), "x");

        assertThat(globTool().execute(globArgs("*.txt"))).contains("notes.txt");
    }

    /** With a backend configured, an omitted path means "the backend", not the agent's own source. */
    @Test
    void globDefaultsToTheBackendRootWhenOneIsConfigured() throws IOException {
        Path backend = Files.createDirectories(root.resolve("backend"));
        FileSystemAccessPolicy withBackend = FileSystemAccessPolicy.host(
                root.toString(), List.of(backend.toString()), NIO_ONLY);

        assertThat(FileSystemWorkspace.host(withBackend.searchRoot()).root())
                .isEqualTo(backend.toRealPath());
    }

    @Test
    void globReportsWhenNothingMatches() {
        assertThat(globTool().execute(globArgs("**/*.rs"))).contains("No files found");
    }

    @Test
    void grepReturnsMatchingLinesWithContext() throws IOException {
        fixture();

        String result = grepTool().execute(grepArgs("go\\(\\)").put("context", 1));

        assertThat(result).contains("App.java:3:        go();");
        assertThat(result).contains("App.java:2-    void run() {");
        assertThat(result).contains("App.java:4-    }");
    }

    @Test
    void grepSupportsFilesWithMatchesAndCountModes() throws IOException {
        fixture();

        String files = grepTool().execute(grepArgs("class").put("output_mode", "files_with_matches"));
        assertThat(files).contains("2 files").contains("App.java").contains("Util.java");

        String counts = grepTool().execute(grepArgs("class").put("output_mode", "count"));
        assertThat(counts).contains("App.java:1").contains("total: 2");
    }

    @Test
    void grepFiltersByFilenameGlob() throws IOException {
        fixture();

        String result = grepTool().execute(
                grepArgs("class").put("output_mode", "files_with_matches").put("glob", "Util.java"));

        assertThat(result).contains("Util.java").doesNotContain("App.java");
    }

    @Test
    void grepSearchesASingleFileWhenPathNamesOne() throws IOException {
        fixture();

        String result = grepTool().execute(grepArgs("void").put(
                "path", root.resolve("App.java").toString()));

        assertThat(result).contains("App.java:2:").doesNotContain("Util.java");
    }

    @Test
    void grepRejectsAnUnknownOutputModeAndAnInvalidRegex() {
        assertThatThrownBy(() -> grepTool().execute(grepArgs("x").put("output_mode", "soup")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("output_mode");
        assertThatThrownBy(() -> grepTool().execute(grepArgs("[")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("invalid regex");
    }

    @Test
    void grepHonoursTheResultCap() throws IOException {
        Files.writeString(root.resolve("many.txt"), "hit\n".repeat(50));
        FileSystemAccessPolicy capped = FileSystemAccessPolicy.host(root.toString(), List.of(),
                new FileSystemAccessPolicy.Settings(FileSystemAccessPolicy.ReadScope.HOST,
                        "rg-definitely-not-installed-xyz", 5, 65_536, 3, 500, 1024 * 1024));

        String result = new GrepTool(capped, FileSystemWorkspace.host(capped.searchRoot()))
                .execute(grepArgs("hit"));

        assertThat(result).contains("limited to 3");
    }

    /** NIO is the complete default; search never reaches for a binary nobody configured. */
    @Test
    void usesNioWithoutProbingWhenRipgrepIsNotConfigured() {
        FileSystemAccessPolicy unconfigured = FileSystemAccessPolicy.host(root.toString(),
                List.of(), FileSystemAccessPolicy.Settings.defaults());

        assertThat(unconfigured.settings().rgPath()).isEmpty();
        assertThat(unconfigured.searchBackend()).isInstanceOf(NioSearchBackend.class);
    }

    /** A broad pattern over a large tree must not pull an unbounded result set into memory. */
    @Test
    void grepHonoursTheOutputByteCeilingAndSaysSo() throws IOException {
        Files.writeString(root.resolve("many.txt"), "hit\n".repeat(200));
        FileSystemAccessPolicy capped = FileSystemAccessPolicy.host(root.toString(), List.of(),
                new FileSystemAccessPolicy.Settings(FileSystemAccessPolicy.ReadScope.HOST,
                        "", 5, 40, 500, 500, 1024 * 1024));

        String result = new GrepTool(capped, FileSystemWorkspace.host(capped.searchRoot()))
                .execute(grepArgs("hit"));

        assertThat(result).contains("output truncated at 40 bytes");
        assertThat(result.lines().filter(line -> line.endsWith(":hit")).count())
                .isLessThan(200);
    }

    @Test
    void fallsBackToNioWhenAConfiguredRipgrepCannotRun() throws IOException {
        fixture();

        assertThat(policy.searchBackend()).isInstanceOf(NioSearchBackend.class);
        assertThat(grepTool().spec().description()).contains("Backend: nio");
        assertThat(globTool().execute(globArgs("**/*.java"))).contains("App.java");
    }

    @Test
    void confinedWorkspaceCannotSearchOutsideItsRoot() throws IOException {
        Path outer = Files.createDirectories(root.resolve("project"));
        Files.writeString(outer.resolve("Inner.java"), "class Inner {}\n");
        Files.writeString(root.resolve("Outer.java"), "class Outer {}\n");

        FileSystemAccessPolicy confined = FileSystemAccessPolicy.confined(
                outer.toString(), NIO_ONLY);
        GlobTool tool = new GlobTool(confined,
                FileSystemWorkspace.readOnly(confined.searchRoot()));

        String result = tool.execute(globArgs("**/*.java"));

        assertThat(result).contains("Inner.java").doesNotContain("Outer.java");
        assertThatThrownBy(() -> tool.execute(
                globArgs("**/*.java").put("path", root.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the readable roots");
    }
}
