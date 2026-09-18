package com.harness.tool.filesystem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cursor paging has to hold one property above all: walking every page must visit every match
 * exactly once, in the same order as a single unpaged call. An offset would break that the moment a
 * file is added or removed mid-walk; a cursor naming the last delivered key does not.
 */
class GlobPagingTest {

    private static final int FILES = 25;
    private static final int PAGE = 4;

    private static final FileSystemAccessPolicy.Settings NIO_ONLY =
            new FileSystemAccessPolicy.Settings(FileSystemAccessPolicy.ReadScope.HOST,
                    "", 5, 65_536, 100, 500, 1024 * 1024);

    @TempDir
    Path root;

    private GlobTool tool;

    @BeforeEach
    void setUp() throws IOException {
        // Spread across three directories so page boundaries land mid-directory, not on them.
        for (int i = 0; i < FILES; i++) {
            Path dir = Files.createDirectories(root.resolve("d" + (i % 3)));
            Files.writeString(dir.resolve(String.format("f%02d.txt", i)), "x");
        }
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(root.toString(), List.of(), NIO_ONLY);
        tool = new GlobTool(policy, FileSystemWorkspace.host(policy.searchRoot()));
    }

    private static ObjectNode args(String pattern) {
        return ToolArguments.MAPPER.createObjectNode().put("pattern", pattern);
    }

    private static List<String> pathsIn(String output) {
        return output.lines()
                .filter(line -> !line.startsWith("Found ") && !line.startsWith("next_cursor:"))
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String cursorIn(String output) {
        return output.lines()
                .filter(line -> line.startsWith("next_cursor:"))
                .map(line -> line.substring("next_cursor:".length()).trim())
                .findFirst()
                .orElse(null);
    }

    /** @throws AssertionError if paging never terminates, which is itself the failure worth catching */
    private List<String> pageAll(String pattern, int limit) {
        List<String> collected = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            ObjectNode arguments = args(pattern).put("limit", limit);
            if (cursor != null) {
                arguments.put("cursor", cursor);
            }
            String output = tool.execute(arguments);
            collected.addAll(pathsIn(output));
            cursor = cursorIn(output);
            if (cursor == null) {
                return collected;
            }
        }
        throw new AssertionError("paging did not terminate after 100 pages");
    }

    @Test
    void everyMatchAppearsExactlyOnceInTheSameOrderAsOneUnpagedCall() {
        List<String> unpaged = pathsIn(tool.execute(args("**/*.txt").put("limit", 200)));

        List<String> paged = pageAll("**/*.txt", PAGE);

        assertThat(unpaged).hasSize(FILES);
        assertThat(paged).doesNotHaveDuplicates().containsExactlyElementsOf(unpaged);
    }

    @Test
    void pagesAreOrderedByRelativePath() throws IOException {
        Path realRoot = root.toRealPath();
        List<String> keys = pathsIn(tool.execute(args("**/*.txt").put("limit", 200))).stream()
                .map(path -> realRoot.relativize(Path.of(path)).toString().replace('\\', '/'))
                .toList();

        assertThat(keys).isSorted();
        // Forward slashes are part of the contract: the cursor embeds these keys.
        assertThat(keys).allSatisfy(key -> assertThat(key).doesNotContain("\\"));
    }

    @Test
    void theFinalPageCarriesNoCursor() {
        String output = tool.execute(args("**/*.txt").put("limit", 200));

        assertThat(output).doesNotContain("next_cursor").doesNotContain("more available");
    }

    @Test
    void anIntermediatePageReportsThatMoreRemain() {
        String output = tool.execute(args("**/*.txt").put("limit", PAGE));

        assertThat(output).contains("more available");
        assertThat(cursorIn(output)).isNotBlank();
    }

    @Test
    void rejectsACursorIssuedForADifferentPatternOrPath() throws IOException {
        Path other = Files.createDirectories(root.resolve("elsewhere"));
        String cursor = cursorIn(tool.execute(args("**/*.txt").put("limit", PAGE)));
        assertThat(cursor).isNotBlank();

        assertThatThrownBy(() -> tool.execute(
                args("**/*.md").put("limit", PAGE).put("cursor", cursor)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not valid for this pattern and path");
        assertThatThrownBy(() -> tool.execute(args("**/*.txt")
                .put("limit", PAGE).put("cursor", cursor).put("path", other.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not valid for this pattern and path");
    }

    @Test
    void rejectsMalformedOrForeignCursorsRatherThanRestartingSilently() {
        String notBase64 = "!!!not-a-cursor!!!";
        String wrongVersion = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"v\":99,\"h\":\"deadbeef\",\"after\":\"x\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> tool.execute(args("**/*.txt").put("cursor", notBase64)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("drop cursor");
        assertThatThrownBy(() -> tool.execute(args("**/*.txt").put("cursor", wrongVersion)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("drop cursor");
    }

    @Test
    void rejectsLimitsOutsideTheAllowedRange() {
        assertThatThrownBy(() -> tool.execute(args("**/*.txt").put("limit", 0)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("limit must be between");
        assertThatThrownBy(() -> tool.execute(args("**/*.txt").put("limit", 201)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("limit must be between");
    }
}
