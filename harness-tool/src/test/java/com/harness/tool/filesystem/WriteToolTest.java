package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriteToolTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private WriteTool tool() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(root.toString(), List.of(),
                FileSystemAccessPolicy.Settings.defaults());
        return new WriteTool(policy, FileSystemWorkspace.host(policy.searchRoot()));
    }

    private static ObjectNode args(String file, String content) {
        ObjectNode node = ToolArguments.MAPPER.createObjectNode();
        node.put("file_path", file);
        node.put("content", content);
        return node;
    }

    @Test
    void createsANewFile() throws IOException {
        Path target = root.resolve("New.java");

        tool().execute(args(target.toString(), "class New {}\n"));

        assertThat(Files.readString(target)).isEqualTo("class New {}\n");
    }

    @Test
    void allowsAnEmptyFile() throws IOException {
        Path target = root.resolve("empty.txt");

        tool().execute(args(target.toString(), ""));

        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readString(target)).isEmpty();
    }

    @Test
    void refusesAnExistingFileAndLeavesItsContentAlone() throws IOException {
        Path target = Files.writeString(root.resolve("Existing.java"), "original\n");

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "replaced\n")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("already exists");
        assertThat(Files.readString(target)).isEqualTo("original\n");
    }

    /**
     * The case a naive "does it exist?" pre-check gets wrong: {@code Files.exists} follows links, so
     * a dangling symlink reads as absent and the write would land on its target instead.
     */
    @Test
    void refusesADanglingLinkAndNeverCreatesItsTarget() throws IOException {
        Path missing = outside.resolve("never-created.txt");
        Path link = root.resolve("dangling.txt");
        Assumptions.assumeTrue(tryCreateSymlink(link, missing), "symlinks unavailable");

        assertThatThrownBy(() -> tool().execute(args(link.toString(), "payload")))
                .isInstanceOf(ToolExecutionException.class);
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void refusesToWriteOutsideTheWritableRoots() throws IOException {
        Path target = outside.resolve("evil.txt");

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "payload")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the writable roots");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void refusesWhenTheParentDirectoryDoesNotExist() {
        Path target = root.resolve("missing-dir").resolve("file.txt");

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "payload")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("cannot resolve path");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void aReadOnlyWorkspaceRefusesWritesOutright() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.confined(
                root.toString(), FileSystemAccessPolicy.Settings.defaults());
        WriteTool confined = new WriteTool(policy, FileSystemWorkspace.readOnly(policy.searchRoot()));

        assertThatThrownBy(() -> confined.execute(args(root.resolve("x.txt").toString(), "x")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("disabled in this scope");
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
