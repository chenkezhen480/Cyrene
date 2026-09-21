package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolExecutionOutcome;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyPatchToolTest {

    @TempDir
    Path root;

    private ApplyPatchTool tool() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                root.toString(), List.of(), FileSystemAccessPolicy.Settings.defaults());
        return new ApplyPatchTool(policy, FileSystemWorkspace.host(root));
    }

    private static ObjectNode args(String patch) {
        return ToolArguments.MAPPER.createObjectNode().put("patch", patch);
    }

    @Test
    void appliesAddUpdateAndDeleteTogetherAndPreservesExistingLineEndings() throws IOException {
        Files.writeString(root.resolve("Update.txt"), "one\r\ntwo\r\n");
        Files.writeString(root.resolve("Delete.txt"), "gone\n");

        ToolExecutionOutcome outcome = tool().executeOutcome(args("""
                *** Begin Patch
                *** Update File: Update.txt
                @@
                 one
                -two
                +changed
                *** Add File: Added.txt
                +new
                +file
                *** Delete File: Delete.txt
                *** End Patch
                """));

        assertThat(Files.readString(root.resolve("Update.txt"))).isEqualTo("one\r\nchanged\r\n");
        assertThat(Files.readString(root.resolve("Added.txt"))).isEqualTo("new\nfile\n");
        assertThat(root.resolve("Delete.txt")).doesNotExist();
        assertThat(outcome.content().json().path("changedFiles").asInt()).isEqualTo(3);
        assertThat(outcome.content().json().path("files")).hasSize(3);
    }

    @Test
    void validatesEveryHunkBeforeWritingAnything() throws IOException {
        Files.writeString(root.resolve("First.txt"), "before\n");
        Files.writeString(root.resolve("Second.txt"), "actual\n");

        assertThatThrownBy(() -> tool().execute(args("""
                *** Update File: First.txt
                @@
                -before
                +after
                *** Update File: Second.txt
                @@
                -stale
                +changed
                """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("re-read the file");

        assertThat(Files.readString(root.resolve("First.txt"))).isEqualTo("before\n");
        assertThat(Files.readString(root.resolve("Second.txt"))).isEqualTo("actual\n");
    }

    @Test
    void refusesTraversalAndExistingAddTargets() throws IOException {
        Path existing = Files.writeString(root.resolve("Existing.txt"), "keep\n");

        assertThatThrownBy(() -> tool().execute(args("""
                *** Add File: ../escaped.txt
                +bad
                """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("'..'");
        assertThat(root.getParent().resolve("escaped.txt")).doesNotExist();

        assertThatThrownBy(() -> tool().execute(args("""
                *** Add File: Existing.txt
                +replace
                """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("already exists");
        assertThat(Files.readString(existing)).isEqualTo("keep\n");
    }

    @Test
    void refusesToUpdateThroughASymbolicLink() throws IOException {
        Path target = Files.writeString(root.resolve("Target.txt"), "safe\n");
        Path link = root.resolve("Link.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlinks unavailable");
        }

        assertThatThrownBy(() -> tool().execute(args("""
                *** Update File: Link.txt
                @@
                -safe
                +unsafe
                """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("symbolic link");
        assertThat(Files.readString(target)).isEqualTo("safe\n");
    }

    @Test
    void createsNewFileInNonExistentSubdirectories() throws IOException {
        tool().execute(args("""
                *** Begin Patch
                *** Add File: deep/nested/pkg/NewClass.java
                +package deep.nested.pkg;
                +public class NewClass {}
                *** End Patch
                """));

        Path created = root.resolve("deep").resolve("nested").resolve("pkg").resolve("NewClass.java");
        assertThat(Files.exists(created)).isTrue();
        assertThat(Files.readString(created)).isEqualTo("package deep.nested.pkg;\npublic class NewClass {}\n");
    }

    @Test
    void refusesFileAndDirectoryStructuralConflictInPatch() {
        assertThatThrownBy(() -> tool().execute(args("""
                *** Begin Patch
                *** Add File: conflict
                +file content
                *** Add File: conflict/child.txt
                +child content
                *** End Patch
                """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("path conflict: a path cannot be both a file and a directory");
    }

    @Test
    void refusesAddingFileWhenAncestorIsExistingRegularFile() throws IOException {
        Files.writeString(root.resolve("conflict.txt"), "regular file\n");

        assertThatThrownBy(() -> tool().execute(args("""
                *** Begin Patch
                *** Add File: conflict.txt/child.txt
                +child content
                *** End Patch
                """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("existing non-directory");
    }

    @Test
    void rollbacksAndCleansUpCreatedEmptyDirectoriesWhenHunkFails() throws IOException {
        Files.writeString(root.resolve("Existing.txt"), "before\n");

        assertThatThrownBy(() -> tool().execute(args("""
                *** Begin Patch
                *** Add File: brand/new/dir/Created.txt
                +new file content
                *** Update File: Existing.txt
                @@
                -stale_mismatch
                +after
                *** End Patch
                """)))
                .isInstanceOf(ToolExecutionException.class);

        assertThat(Files.exists(root.resolve("brand"))).isFalse();
    }

    @Test
    void rollbackPreservesPreExistingDirectories() throws IOException {
        Path existingDir = Files.createDirectories(root.resolve("preserved").resolve("existing_dir"));
        Path stayFile = Files.writeString(existingDir.resolve("stay.txt"), "stay\n");
        Path regularTarget = Files.writeString(root.resolve("Target.txt"), "safe\n");
        Path link = root.resolve("Link.txt");
        try {
            Files.createSymbolicLink(link, regularTarget);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlinks unavailable");
        }

        assertThatThrownBy(() -> tool().execute(args("""
                *** Begin Patch
                *** Add File: preserved/existing_dir/sub/Created.txt
                +new file content
                *** Update File: Link.txt
                @@
                -safe
                +unsafe
                *** End Patch
                """)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("symbolic link");

        assertThat(Files.exists(existingDir)).isTrue();
        assertThat(Files.exists(stayFile)).isTrue();
        assertThat(Files.exists(existingDir.resolve("sub"))).isFalse();
    }
}
