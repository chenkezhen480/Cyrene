package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Discovery tool: locate candidate files by glob pattern within a project root.
 * Path boundary enforced — cannot escape root directory.
 * Sensitive files (.env*, *.pem, *.key, etc.) are excluded.
 */
public class CodeGlobTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CodeGlobTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 50;

    /** Default sensitive file patterns to exclude. */
    private static final Set<String> DEFAULT_EXCLUDES = Set.of(
            ".env*", "*.pem", "*.key", "*credentials*", "*secret*",
            "application*.yml", "application*.yaml", "*.jks", "*.p12", "*.keystore"
    );

    private final Path rootDir;
    private final Set<String> excludePatterns;

    public CodeGlobTool(Path rootDir, Set<String> additionalExcludes) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        Set<String> allExcludes = new java.util.HashSet<>(DEFAULT_EXCLUDES);
        if (additionalExcludes != null) {
            allExcludes.addAll(additionalExcludes);
        }
        this.excludePatterns = allExcludes;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "code_glob",
                "Find files matching a glob pattern within the project root. " +
                "Use to locate candidate source files (e.g. '**/*.java', '**/controller*'). " +
                "Returns up to 50 file paths.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("pattern",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Glob pattern, e.g. '**/*.java', '**/controller*.*'")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("pattern"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String pattern = arguments.has("pattern") ? arguments.get("pattern").asText().trim() : null;
        if (pattern == null || pattern.isEmpty()) {
            return "ERROR: 'pattern' is required";
        }

        log.debug("[CodeGlob] Searching: {} in {}", pattern, rootDir);

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> matches = new ArrayList<>();

            Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip hidden directories and target/build directories
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dirName.startsWith(".") || dirName.equals("target") || dirName.equals("build")
                            || dirName.equals("node_modules") || dirName.equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;

                    Path relative = rootDir.relativize(file);
                    String relativeStr = relative.toString().replace('\\', '/');

                    // Check sensitive file exclusion
                    if (isSensitiveFile(relativeStr)) return FileVisitResult.CONTINUE;

                    // Check glob match (match against relative path)
                    if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                        matches.add(relativeStr);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) {
                return "No files found matching pattern: " + pattern;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(matches.size()).append(" files:\n");
            for (String m : matches) {
                sb.append(m).append("\n");
            }
            return sb.toString();

        } catch (IOException e) {
            log.error("[CodeGlob] IO error: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    private boolean isSensitiveFile(String relativePath) {
        String lower = relativePath.toLowerCase();
        String fileName = Path.of(relativePath).getFileName().toString().toLowerCase();
        for (String exclude : excludePatterns) {
            // Simple glob-like matching
            String regex = exclude.replace(".", "\\.").replace("*", ".*");
            if (fileName.matches(regex) || lower.matches(".*" + regex + ".*")) {
                return true;
            }
        }
        return false;
    }
}
