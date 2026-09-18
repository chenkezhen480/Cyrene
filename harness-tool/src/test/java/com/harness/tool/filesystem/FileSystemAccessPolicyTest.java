package com.harness.tool.filesystem;

import com.harness.core.exception.ToolExecutionException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The boundary these tests defend: reads reach the whole machine, writes reach the configured roots
 * and nothing else, and no path spelling gets around that.
 */
class FileSystemAccessPolicyTest {

    private static final String TOOL = "edit";

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private static FileSystemAccessPolicy.Settings settings() {
        return new FileSystemAccessPolicy.Settings(
                FileSystemAccessPolicy.ReadScope.HOST, "rg-definitely-not-installed",
                5, 65_536, 50, 500, 1024 * 1024);
    }

    private FileSystemAccessPolicy hostPolicy(Path... backendRoots) {
        return FileSystemAccessPolicy.host(root.toString(),
                java.util.Arrays.stream(backendRoots).map(Path::toString).toList(), settings());
    }

    @Test
    void readsAnywhereTheProcessCanReadButWritesOnlyInsideTheRoot() throws IOException {
        Path config = Files.writeString(outside.resolve("redis.conf"), "port 6379");
        FileSystemAccessPolicy policy = hostPolicy();

        assertThat(policy.resolveReadable(TOOL, config)).isEqualTo(config.toRealPath());
        assertThatThrownBy(() -> policy.resolveWritable(TOOL, outside.resolve("evil.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the writable roots");
    }

    @Test
    void writesInsideTheAgentRootAndInsideAConfiguredBackendRoot() throws IOException {
        Path backend = Files.createDirectories(outside.resolve("backend"));
        FileSystemAccessPolicy policy = hostPolicy(backend);

        assertThat(policy.resolveWritable(TOOL, root.resolve("new.txt")))
                .isEqualTo(root.toRealPath().resolve("new.txt"));
        assertThat(policy.resolveWritable(TOOL, backend.resolve("new.txt")))
                .isEqualTo(backend.toRealPath().resolve("new.txt"));
        assertThat(policy.writesEnabled()).isTrue();
    }

    @Test
    void rejectsParentTraversalWithoutCreatingAnything() throws IOException {
        Path nested = Files.createDirectories(root.resolve("a").resolve("b"));

        assertThatThrownBy(() -> hostPolicy().resolveWritable(
                TOOL, nested.resolve("../../../escaped.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("'..'");
        assertThat(Files.exists(root.getParent().resolve("escaped.txt"))).isFalse();
    }

    @Test
    void rejectsAPathEndingInADotComponent() throws IOException {
        Path nested = Files.createDirectories(root.resolve("sub"));

        assertThatThrownBy(() -> hostPolicy().resolveWritable(TOOL, nested.resolve("..")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("'..'");
    }

    @Test
    void rejectsRelativeAndRootOnlyPaths() {
        FileSystemAccessPolicy policy = hostPolicy();

        assertThatThrownBy(() -> policy.resolveReadable(TOOL, Path.of("relative.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("must be absolute");
        assertThatThrownBy(() -> policy.resolveReadable(TOOL, Path.of("")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("must be absolute");
        assertThatThrownBy(() -> policy.resolveWritable(TOOL, Path.of("C:\\")))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void rejectsDriveRelativeAndRootRelativeFormsOnWindows() {
        Assumptions.assumeTrue(File.separatorChar == '\\', "Windows-only path forms");

        assertThatThrownBy(() -> hostPolicy().resolveReadable(TOOL, Path.of("D:foo")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("must be absolute");
        assertThatThrownBy(() -> hostPolicy().resolveReadable(TOOL, Path.of("\\foo")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("must be absolute");
    }

    /**
     * Guards against anyone "tightening" the containment check into a case-sensitive string compare.
     * NTFS is case-insensitive, so a different-cased spelling of an in-root path is still in-root.
     */
    @Test
    void treatsAnInRootPathWithDifferentCaseAsInRoot() throws IOException {
        Assumptions.assumeTrue(File.separatorChar == '\\', "Windows-only casing behaviour");
        Path created = Files.writeString(root.resolve("Case.txt"), "x");

        Path shouted = Path.of(root.toRealPath().toString().toUpperCase(Locale.ROOT))
                .resolve("case.txt");

        assertThat(hostPolicy().resolveWritable(TOOL, shouted)).isEqualTo(created.toRealPath());
    }

    @Test
    void refusesAWritePathThatResolvesThroughALinkLeavingTheRoot() throws IOException {
        Path link = root.resolve("escape");
        Assumptions.assumeTrue(tryCreateSymlink(link, outside), "symlinks unavailable");

        assertThatThrownBy(() -> hostPolicy().resolveWritable(TOOL, link.resolve("evil.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the writable roots");
    }

    /**
     * A dangling link sitting inside the root is a plain directory entry there, so the policy
     * resolves its parent and hands back the link itself. That is the safe answer: a later
     * {@code CREATE_NEW} or rename then acts on the entry rather than escaping to whatever the link
     * points at. The refusal itself lives one layer up — {@code WriteTool} via {@code CREATE_NEW},
     * {@code EditTool} via {@code isRegularFile}.
     */
    @Test
    void resolvesADanglingLinkToTheEntryItselfRatherThanFollowingIt() throws IOException {
        Path missing = outside.resolve("never-created.txt");
        Path link = root.resolve("dangling.txt");
        Assumptions.assumeTrue(tryCreateSymlink(link, missing), "symlinks unavailable");

        assertThat(hostPolicy().resolveWritable(TOOL, link))
                .isEqualTo(root.toRealPath().resolve("dangling.txt"));
    }

    @Test
    void confinedScopeReadsOnlyItsRootAndDisablesWritesEntirely() throws IOException {
        Path readable = Files.writeString(outside.resolve("secret.conf"), "x");
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.confined(
                root.toString(), settings());

        assertThat(policy.writesEnabled()).isFalse();
        assertThat(policy.searchRoot()).isEqualTo(root.toRealPath());
        assertThat(policy.resolveReadable(TOOL, root)).isEqualTo(root.toRealPath());
        assertThatThrownBy(() -> policy.resolveReadable(TOOL, readable))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the readable roots");
        assertThatThrownBy(() -> policy.resolveWritable(TOOL, root.resolve("x.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void workspaceScopedReadsAreLimitedToTheConfiguredRoots() throws IOException {
        Path readable = Files.writeString(outside.resolve("nginx.conf"), "x");
        FileSystemAccessPolicy policy = FileSystemAccessPolicy.host(root.toString(), List.of(),
                new FileSystemAccessPolicy.Settings(FileSystemAccessPolicy.ReadScope.WORKSPACE,
                        "rg-definitely-not-installed", 5, 65_536, 50, 500, 1024 * 1024));

        assertThat(policy.resolveReadable(TOOL, root.resolve("a.txt").getParent()))
                .isEqualTo(root.toRealPath());
        assertThatThrownBy(() -> policy.resolveReadable(TOOL, readable))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the readable roots");
    }

    /**
     * A junction is the case {@code Files.isSymbolicLink} gets wrong on Windows — it is false for
     * them, since junctions carry a different reparse tag. Only resolving the real path catches it,
     * so this pins that the resolver actually does.
     */
    @Test
    void refusesAWriteThroughAWindowsJunctionLeavingTheRoot() throws IOException {
        Path elsewhere = Files.createDirectories(outside.resolve("elsewhere"));
        Path writable = Files.createDirectories(root.resolve("writable"));
        Path junction = writable.resolve("escape");
        Assumptions.assumeTrue(tryCreateJunction(junction, elsewhere), "junctions unavailable");

        assertThatThrownBy(() -> hostPolicy(writable).resolveWritable(TOOL, junction.resolve("x.txt")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside the writable roots");
    }

    /**
     * The walk has to skip a junction explicitly. {@code FileTreeWalker} decides whether to descend
     * from {@code isDirectory() && !isSymbolicLink()}, and both are true for a junction — so without
     * the {@code isOther()} check a confined search would quietly read the whole linked tree.
     */
    @Test
    void aConfinedSearchDoesNotDescendIntoAWindowsJunction() throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("Outside.java"), "class Outside {}\n");
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("Inside.java"), "class Inside {}\n");
        Path junction = project.resolve("linked");
        Assumptions.assumeTrue(tryCreateJunction(junction, elsewhere), "junctions unavailable");

        FileSystemAccessPolicy confined = FileSystemAccessPolicy.confined(
                project.toString(), settings());
        GlobTool tool = new GlobTool(confined,
                FileSystemWorkspace.readOnly(confined.searchRoot()));

        String result = tool.execute(
                ToolArguments.MAPPER.createObjectNode().put("pattern", "**/*.java"));

        assertThat(result).contains("Inside.java").doesNotContain("Outside.java");
    }

    /**
     * Junctions have no Java API, so this test shells out to {@code mklink /J}. That is a test-only
     * step — no production code in this package starts a shell.
     */
    private static boolean tryCreateJunction(Path link, Path target) {
        if (File.separatorChar != '\\') {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    "cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
