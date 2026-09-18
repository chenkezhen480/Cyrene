package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadToolTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private ReadTool tool() {
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(root.toString(), List.of(),
                FileSystemAccessPolicy.Settings.defaults());
        return new ReadTool(policy, FileSystemWorkspace.host(policy.searchRoot()));
    }

    private static ObjectNode args(String file) {
        ObjectNode node = ToolArguments.MAPPER.createObjectNode();
        node.put("file_path", file);
        return node;
    }

    @Test
    void returnsTheFileVerbatimWithLineNumbers() throws IOException {
        Path target = Files.writeString(root.resolve("App.java"), "one\ntwo\nthree\n");

        String output = tool().execute(args(target.toString()));

        assertThat(output).contains("1\tone").contains("2\ttwo").contains("3\tthree");
    }

    /** No summarisation and no rewriting: the content must survive a round trip exactly. */
    @Test
    void doesNotSummariseOrAlterContent() throws IOException {
        Path target = Files.writeString(root.resolve("conf.ini"),
                "[redis]\nhost = 127.0.0.1\r\n");

        String output = tool().execute(args(target.toString()));

        assertThat(output).contains("[redis]").contains("host = 127.0.0.1");
    }

    @Test
    void honoursOffsetAndLimitAndSaysHowToContinue() throws IOException {
        Path target = Files.writeString(root.resolve("App.java"),
                "l1\nl2\nl3\nl4\nl5\n");

        ObjectNode args = args(target.toString()).put("offset", 3).put("limit", 2);
        String output = tool().execute(args);

        assertThat(output).contains("3\tl3").contains("4\tl4");
        assertThat(output).doesNotContain("l2").doesNotContain("l5");
        assertThat(output).contains("continue with offset 5");
    }

    @Test
    void readsOutsideTheWorkspaceInHostScope() throws IOException {
        Path target = Files.writeString(outside.resolve("redis.conf"), "port 6379\n");

        assertThat(tool().execute(args(target.toString()))).contains("port 6379");
    }

    @Test
    void refusesBinaryFiles() throws IOException {
        Path target = root.resolve("blob.bin");
        Files.write(target, new byte[]{0x50, 0x4B, 0x00, 0x01, 0x02});

        assertThatThrownBy(() -> tool().execute(args(target.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("binary");
    }

    @Test
    void refusesDirectoriesAndMissingFiles() {
        assertThatThrownBy(() -> tool().execute(args(root.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not a regular file");
        assertThatThrownBy(() -> tool().execute(args(root.resolve("nope.txt").toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("cannot resolve path");
    }

    @Test
    void resolvesARelativePathAgainstTheWorkspaceRoot() throws IOException {
        Files.writeString(root.resolve("relative.txt"), "content\n");

        assertThat(tool().execute(args("relative.txt"))).contains("content");
    }

    @Test
    void reportsWhenThereIsNothingAtTheRequestedOffset() throws IOException {
        Path target = Files.writeString(root.resolve("short.txt"), "only\n");

        assertThat(tool().execute(args(target.toString()).put("offset", 99)))
                .contains("no content at offset 99");
    }
}
