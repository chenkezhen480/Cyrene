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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditToolTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private EditTool tool() {
        return tool(new FileSystemAccessPolicy.Settings(FileSystemAccessPolicy.ReadScope.HOST,
                "rg-definitely-not-installed", 5, 65_536, 50, 500, 1024 * 1024));
    }

    private EditTool tool(FileSystemAccessPolicy.Settings settings) {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(
                root.toString(), List.of(), settings);
        return new EditTool(policy, FileSystemWorkspace.host(policy.searchRoot()));
    }

    private static ObjectNode args(String file, String oldText, String newText) {
        ObjectNode node = ToolArguments.MAPPER.createObjectNode();
        node.put("file_path", file);
        node.put("old_string", oldText);
        node.put("new_string", newText);
        return node;
    }

    private Path file(String name, String content) throws IOException {
        return Files.writeString(root.resolve(name), content);
    }

    @Test
    void replacesASingleMatch() throws IOException {
        Path target = file("App.java", "class App {\n    int x = 1;\n}\n");

        tool().execute(args(target.toString(), "int x = 1;", "int x = 2;"));

        assertThat(Files.readString(target)).isEqualTo("class App {\n    int x = 2;\n}\n");
    }

    @Test
    void failsWhenOldStringIsAbsentAndLeavesTheFileAlone() throws IOException {
        Path target = file("App.java", "class App {}\n");

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "missing", "x")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("old_string not found");
        assertThat(Files.readString(target)).isEqualTo("class App {}\n");
    }

    @Test
    void failsOnAmbiguousMatchUnlessReplaceAllIsSet() throws IOException {
        Path target = file("App.java", "a\na\n");

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "a", "b")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("more than once");
        assertThat(Files.readString(target)).isEqualTo("a\na\n");

        ObjectNode replaceAll = args(target.toString(), "a", "b").put("replace_all", true);
        tool().execute(replaceAll);

        assertThat(Files.readString(target)).isEqualTo("b\nb\n");
    }

    /** "aa" occurs twice in "aaa" when overlap is allowed; refusing is the safer failure. */
    @Test
    void treatsOverlappingOccurrencesAsAmbiguous() throws IOException {
        Path target = file("App.java", "aaa\n");

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "aa", "b")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void rejectsAnEmptyOrNoOpEdit() throws IOException {
        Path target = file("App.java", "same\n");

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "", "x")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> tool().execute(args(target.toString(), "same", "same")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("no change");
    }

    /** Callers copy text out of a file they read, so LF arguments against a CRLF file are normal. */
    @Test
    void alignsLfArgumentsToACrlfFileAndLeavesOtherLinesByteIdentical() throws IOException {
        Path target = file("App.java", "one\r\ntwo\r\nthree\r\n");

        tool().execute(args(target.toString(), "one\ntwo", "1\n2"));

        assertThat(Files.readString(target)).isEqualTo("1\r\n2\r\nthree\r\n");
    }

    @Test
    void alignsCrlfArgumentsToAnLfFile() throws IOException {
        Path target = file("App.java", "one\ntwo\nthree\n");

        tool().execute(args(target.toString(), "one\r\ntwo", "1\r\n2"));

        assertThat(Files.readString(target)).isEqualTo("1\n2\nthree\n");
    }

    @Test
    void leavesAMixedEndingFileMixed() throws IOException {
        Path target = file("App.java", "a\r\nb\nc\n");

        tool().execute(args(target.toString(), "b", "B"));

        assertThat(Files.readString(target)).isEqualTo("a\r\nB\nc\n");
    }

    @Test
    void keepsAUtf8Bom() throws IOException {
        Path target = file("App.java", "﻿hello\nworld\n");

        tool().execute(args(target.toString(), "world", "there"));

        byte[] bytes = Files.readAllBytes(target);
        assertThat(new byte[]{bytes[0], bytes[1], bytes[2]})
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(Files.readString(target)).isEqualTo("﻿hello\nthere\n");
    }

    @Test
    void refusesANonUtf8FileWithoutTouchingIt() throws IOException {
        Path target = root.resolve("legacy.conf");
        byte[] gbk = "中文=1\n".getBytes("GBK");
        Files.write(target, gbk);

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "1", "2")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("UTF-8");
        assertThat(Files.readAllBytes(target)).isEqualTo(gbk);
    }

    @Test
    void refusesPathsOutsideTheWritableRootsEvenThoughEditingIsAuthorized() throws IOException {
        Path victim = Files.writeString(outside.resolve("victim.conf"), "keep me\n");

        assertThatThrownBy(() -> tool().execute(args(victim.toString(), "keep", "lose")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the writable roots");
        assertThat(Files.readString(victim)).isEqualTo("keep me\n");
    }

    @Test
    void refusesToEditThroughASymbolicLink() throws IOException {
        Path victim = Files.writeString(outside.resolve("victim.conf"), "keep me\r\n");
        Path link = root.resolve("link.conf");
        Assumptions.assumeTrue(tryCreateSymlink(link, victim), "symlinks unavailable");

        assertThatThrownBy(() -> tool().execute(args(link.toString(), "keep", "lose")))
                .isInstanceOf(ToolExecutionException.class);
        assertThat(Files.readString(victim)).isEqualTo("keep me\r\n");
    }

    /** A link whose target does not exist is invisible to a following existence check. */
    @Test
    void refusesToEditADanglingLink() throws IOException {
        Path missing = outside.resolve("never-created.txt");
        Path link = root.resolve("dangling.txt");
        Assumptions.assumeTrue(tryCreateSymlink(link, missing), "symlinks unavailable");

        assertThatThrownBy(() -> tool().execute(args(link.toString(), "a", "b")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not an existing regular file");
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void refusesADirectoryAndAFileOverTheSizeLimit() throws IOException {
        Path directory = Files.createDirectories(root.resolve("sub"));

        assertThatThrownBy(() -> tool().execute(args(directory.toString(), "a", "b")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not an existing regular file");

        Path big = file("big.txt", "x".repeat(4096));
        FileSystemAccessPolicy.Settings tiny = new FileSystemAccessPolicy.Settings(
                FileSystemAccessPolicy.ReadScope.HOST, "rg-definitely-not-installed",
                5, 65_536, 50, 500, 1024);
        assertThatThrownBy(() -> tool(tiny).execute(args(big.toString(), "x", "y")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("above the");
    }

    @Test
    void leavesNoTemporaryFileBehindOnSuccessOrFailure() throws IOException {
        Path target = file("App.java", "value = 1;\n");

        tool().execute(args(target.toString(), "value = 1;", "value = 2;"));
        assertThat(tempFiles()).isEmpty();

        assertThatThrownBy(() -> tool().execute(args(target.toString(), "nope", "x")))
                .isInstanceOf(ToolExecutionException.class);
        assertThat(tempFiles()).isEmpty();
    }

    @Test
    void keepsPosixPermissionsOfTheReplacedFile() throws IOException {
        Path target = file("script.sh", "echo hi\n");
        Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"),
                "no POSIX permissions on this filesystem");
        Files.setPosixFilePermissions(target,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        tool().execute(args(target.toString(), "hi", "bye"));

        assertThat(Files.getPosixFilePermissions(target))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private List<Path> tempFiles() throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(".cyrene-edit"))
                    .toList();
        }
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
