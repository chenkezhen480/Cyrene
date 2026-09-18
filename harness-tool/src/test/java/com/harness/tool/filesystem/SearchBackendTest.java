package com.harness.tool.filesystem;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both backends must answer the same. If they did not, whether the Agent can see a file would
 * depend on whether ripgrep happens to be installed on the host — a difference nobody would notice
 * until it mattered.
 */
class SearchBackendTest {

    private static final String RG = "rg";

    /** Generous enough that no fixture in this class reaches it; the cap has its own test. */
    private static final int BUDGET = 1 << 20;

    /**
     * Set by CI, which installs ripgrep. Without it a missing binary skips these tests, because the
     * NIO backend is the complete default and needing ripgrep is not a release requirement — but a
     * CI run must not pass by quietly skipping the only thing that checks the two agree.
     */
    private static final boolean REQUIRED = "true".equalsIgnoreCase(System.getenv("CYRENE_REQUIRE_RG"));

    @TempDir
    Path root;

    @BeforeEach
    void requireRipgrep() {
        boolean available = RipgrepSearchBackend.isAvailable(RG);
        if (REQUIRED) {
            assertThat(available)
                    .as("CYRENE_REQUIRE_RG is set, so ripgrep must be runnable or these comparisons "
                            + "would pass by skipping")
                    .isTrue();
        } else {
            Assumptions.assumeTrue(available, "ripgrep is not installed");
        }
    }

    private FileSystemAccessPolicy.Settings settings() {
        return new FileSystemAccessPolicy.Settings(FileSystemAccessPolicy.ReadScope.HOST,
                RG, 10, 65_536, 100, 500, 1024 * 1024);
    }

    private void fixture() throws IOException {
        Files.writeString(root.resolve("App.java"), "class App {\n    void run() {}\n}\n");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("Util.java"), "package sub;\nclass Util {\n}\n");
        Files.writeString(root.resolve("notes.md"), "# notes\nclass App is documented here\n");
        Files.writeString(root.resolve("crlf.txt"), "alpha\r\nrun\r\nomega\r\n");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target").resolve("Skipped.java"), "class App {}\n");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "class App {}\n");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules").resolve("dep.java"), "class App {}\n");
        Files.writeString(root.resolve(".env"), "APP_MODE=dev\n");
    }

    @Test
    void globReturnsTheSameFiles() throws IOException {
        fixture();

        FileSearchBackend.Page ripgrep =
                new RipgrepSearchBackend(settings()).glob(root, "**/*.java", null, 100);
        FileSearchBackend.Page nio = new NioSearchBackend().glob(root, "**/*.java", null, 100);

        assertThat(ripgrep.paths()).isNotEmpty().isEqualTo(nio.paths());
        assertThat(ripgrep.hasMore()).isEqualTo(nio.hasMore());
    }

    @Test
    void globReachesHiddenFilesTheSameWay() throws IOException {
        fixture();

        assertThat(new RipgrepSearchBackend(settings()).glob(root, "**/.env", null, 100).paths())
                .isEqualTo(new NioSearchBackend().glob(root, "**/.env", null, 100).paths())
                .isNotEmpty();
    }

    @Test
    void grepReturnsTheSameMatchesIncludingContext() throws IOException {
        fixture();

        List<String> ripgrep = flatten(new RipgrepSearchBackend(settings())
                .grep(root, "run|class", null, 1, 100, BUDGET));
        List<String> nio = flatten(new NioSearchBackend()
                .grep(root, "run|class", null, 1, 100, BUDGET));

        assertThat(ripgrep).isNotEmpty().isEqualTo(nio);
    }

    @Test
    void grepHandlesCrlfLinesTheSameWay() throws IOException {
        fixture();

        assertThat(flatten(new RipgrepSearchBackend(settings()).grep(root, "run", null, 0, 100, BUDGET)))
                .isEqualTo(flatten(new NioSearchBackend().grep(root, "run", null, 0, 100, BUDGET)))
                .isNotEmpty();
    }

    @Test
    void bothBackendsSkipTheSameBuildDirectories() throws IOException {
        fixture();

        for (FileSearchBackend backend : List.of(
                new RipgrepSearchBackend(settings()), new NioSearchBackend())) {
            List<Path> java = backend.glob(root, "**/*.java", null, 100).paths();
            assertThat(java).noneMatch(path -> path.toString().contains("node_modules"));
            assertThat(java).noneMatch(path -> path.toString().contains("target"));
            assertThat(java).noneMatch(path -> path.toString().contains(".git"));
        }
    }

    private static List<String> flatten(FileSearchBackend.GrepResult result) {
        List<String> lines = new ArrayList<>();
        for (FileSearchBackend.GrepFileResult file : result.files()) {
            for (FileSearchBackend.GrepLine line : file.matches()) {
                lines.add(file.path() + ":" + line.lineNumber() + ":" + line.text()
                        + "|" + String.join(",", line.before())
                        + "|" + String.join(",", line.after()));
            }
        }
        return lines;
    }
}
