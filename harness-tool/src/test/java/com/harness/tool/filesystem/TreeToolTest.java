package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeToolTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private TreeTool tool;
    private Path realRoot;

    @BeforeEach
    void setUp() throws IOException {
        realRoot = root.toRealPath();
        Files.createDirectories(root.resolve("src").resolve("main"));
        Files.createDirectories(root.resolve("src").resolve("test"));
        Files.writeString(root.resolve("src").resolve("main").resolve("App.java"), "class App {}");
        Files.writeString(root.resolve("src").resolve("test").resolve("AppTest.java"), "class T {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target").resolve("Generated.java"), "class G {}");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "x");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules").resolve("dep.js"), "x");

        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                root.toString(), List.of(), FileSystemAccessPolicy.Settings.defaults());
        tool = new TreeTool(policy, FileSystemWorkspace.host(policy.searchRoot()));
    }

    private static ObjectNode args() {
        return ToolArguments.MAPPER.createObjectNode();
    }

    @Test
    void showsDirectoriesWithASlashAndFilesWithout() {
        String output = tool.execute(args());

        assertThat(output).contains("├── src/").contains("└── pom.xml");
        assertThat(output).startsWith(realRoot.toString());
    }

    @Test
    void hidesBuildAndVcsDirectoriesTheSameWaySearchDoes() {
        String output = tool.execute(args().put("depth", 3));

        assertThat(output).doesNotContain("target").doesNotContain(".git")
                .doesNotContain("node_modules");
        assertThat(output).contains("App.java");
    }

    @Test
    void defaultDepthShowsTwoLevels() {
        String output = tool.execute(args());

        assertThat(output).contains("src/").contains("main/").contains("test/");
        assertThat(output).doesNotContain("App.java");
    }

    @Test
    void aLargerDepthReachesTheFiles() {
        assertThat(tool.execute(args().put("depth", 3))).contains("App.java");
    }

    @Test
    void rejectsDepthsOutsideTheAllowedRange() {
        assertThatThrownBy(() -> tool.execute(args().put("depth", 0)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("depth must be between");
        assertThatThrownBy(() -> tool.execute(args().put("depth", 7)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("depth must be between");
    }

    /** Links are shown as leaves and never descended — a junction is an "other", not a symlink. */
    @Test
    void showsLinksWithoutFollowingThem() throws IOException {
        Path walked = Files.createDirectories(outside.resolve("walked"));
        Files.writeString(walked.resolve("Secret.java"), "class Secret {}");
        Path link = root.resolve("linked");
        Assumptions.assumeTrue(tryCreateSymlink(link, walked), "symlinks unavailable");

        String output = tool.execute(args().put("depth", 4));

        assertThat(output).contains("linked@").doesNotContain("Secret.java");
    }

    @Test
    void aConfinedWorkspaceCannotBrowseOutsideItsRoot() throws IOException {
        Path outer = Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("Outside.java"), "class Outside {}");

        FileSystemAccessPolicy confined = FileSystemAccessPolicy.confined(
                outer.toString(), FileSystemAccessPolicy.Settings.defaults());
        TreeTool scoped = new TreeTool(confined,
                FileSystemWorkspace.readOnly(confined.searchRoot()));

        assertThatThrownBy(() -> scoped.execute(args().put("path", root.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the readable roots");
    }

    @Test
    void refusesAPathThatIsNotADirectory() {
        assertThatThrownBy(() -> tool.execute(args().put("path", root.resolve("pom.xml").toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not a directory");
    }

    /** Truncation must be announced; a short tree that looks complete is the failure mode to avoid. */
    @Test
    void reportsTruncationInsteadOfStoppingSilently() throws IOException {
        Path wide = Files.createDirectories(root.resolve("wide"));
        for (int i = 0; i < 2100; i++) {
            Files.createFile(wide.resolve(String.format("f%04d.txt", i)));
        }

        String output = tool.execute(args().put("path", wide.toString()).put("depth", 1));

        assertThat(output).contains("truncated at 2000 entries");
    }

    private static boolean tryCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
