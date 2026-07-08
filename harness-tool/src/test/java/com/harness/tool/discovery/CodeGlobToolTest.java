package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CodeGlobToolTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private CodeGlobTool createTool() {
        return new CodeGlobTool(tempDir, Set.of());
    }

    private ObjectNode args(String pattern) {
        ObjectNode node = mapper.createObjectNode();
        node.put("pattern", pattern);
        return node;
    }

    @Test
    void matchesRootLevelMdFiles(@TempDir Path dir) throws IOException {
        // Create root-level .md files
        Files.writeString(dir.resolve("CLAUDE.md"), "# CLAUDE");
        Files.writeString(dir.resolve("README.md"), "# README");
        // Create nested .md file
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/api.md"), "# API");

        CodeGlobTool tool = new CodeGlobTool(dir, Set.of());
        String result = tool.execute(args("**/*.md"));

        assertTrue(result.contains("CLAUDE.md"), "Should find root-level CLAUDE.md, got: " + result);
        assertTrue(result.contains("README.md"), "Should find root-level README.md, got: " + result);
        assertTrue(result.contains("docs/api.md"), "Should find nested docs/api.md, got: " + result);
    }

    @Test
    void matchesJavaFilesInSubdirectories(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("src/main/java/com/example/App.java"), "class App {}");
        Files.writeString(dir.resolve("src/main/java/com/example/Utils.java"), "class Utils {}");

        CodeGlobTool tool = new CodeGlobTool(dir, Set.of());
        String result = tool.execute(args("**/*.java"));

        assertTrue(result.contains("App.java"), "Should find App.java, got: " + result);
        assertTrue(result.contains("Utils.java"), "Should find Utils.java, got: " + result);
    }

    @Test
    void matchesSpecificFilename(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/pom.xml"), "<project/>");

        CodeGlobTool tool = new CodeGlobTool(dir, Set.of());
        String result = tool.execute(args("**/pom.xml"));

        assertTrue(result.contains("pom.xml"), "Should find pom.xml files, got: " + result);
        assertTrue(result.contains("Found 2 files"), "Should find 2 pom.xml files, got: " + result);
    }

    @Test
    void noMatchesReturnsMessage(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("App.java"), "class App {}");

        CodeGlobTool tool = new CodeGlobTool(dir, Set.of());
        String result = tool.execute(args("**/*.py"));

        assertTrue(result.contains("No files found"), "Should report no matches, got: " + result);
    }

    @Test
    void excludesSensitiveFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".env"), "SECRET=1");
        Files.writeString(dir.resolve("app.pem"), "key");
        Files.writeString(dir.resolve("App.java"), "class App {}");

        CodeGlobTool tool = new CodeGlobTool(dir, Set.of());
        String result = tool.execute(args("**/*"));

        assertTrue(result.contains("App.java"), "Should find App.java, got: " + result);
        assertFalse(result.contains(".env"), "Should exclude .env, got: " + result);
        assertFalse(result.contains("app.pem"), "Should exclude .pem, got: " + result);
    }

    @Test
    void skipsHiddenAndBuildDirectories(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".git/objects"));
        Files.writeString(dir.resolve(".git/config"), "config");
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("target/classes/App.class"), "bytes");
        Files.writeString(dir.resolve("App.java"), "class App {}");

        CodeGlobTool tool = new CodeGlobTool(dir, Set.of());
        String result = tool.execute(args("**/*"));

        assertTrue(result.contains("App.java"), "Should find App.java, got: " + result);
        assertFalse(result.contains(".git"), "Should skip .git, got: " + result);
        assertFalse(result.contains("target"), "Should skip target, got: " + result);
    }
}
