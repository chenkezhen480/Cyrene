package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.model.ToolResult;
import com.harness.core.model.ToolSpec;
import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
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
 * Discovery tool: locate candidate files by glob pattern within the project root.
 * Path boundary enforced — cannot escape root directory.
 * Sensitive files (.env*, *.pem, *.key, etc.) are excluded.
 */
public class CodeGlobTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CodeGlobTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Default sensitive file patterns to exclude. */
    private static final Set<String> DEFAULT_EXCLUDES = Set.of(
            ".env*", "*.pem", "*.key", "*credentials*", "*secret*",
            "application*.yml", "application*.yaml", "*.jks", "*.p12", "*.keystore"
    );

    private final Path rootDir;
    private final Set<String> excludePatterns;
    private final int maxResults;

    public CodeGlobTool(Path rootDir, Set<String> additionalExcludes) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        Set<String> allExcludes = new java.util.HashSet<>(DEFAULT_EXCLUDES);
        if (additionalExcludes != null) {
            allExcludes.addAll(additionalExcludes);
        }
        this.excludePatterns = allExcludes;
        this.maxResults = EnvConfig.get().getInt(EnvKey.TOOL_MAX_RESULTS, 100);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "code_glob",
                "Find files matching a glob pattern within the project directory. " +
                "Use to locate project source files for API route discovery (e.g. '**/*Controller.java', '**/routes*'). " +
                "Returns up to " + maxResults + " file paths.",
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

        // Project not initialized — rootDir is still the default "."
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        if (rootDir.equals(cwd)) {
            throw new ToolExecutionException("code_glob",
                    "Project path not configured. Initialize via project discovery scan first.");
        }

        log.debug("[CodeGlob] Searching: {} in {}", pattern, rootDir);

        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            // Fallback matcher for root-level files: strip leading **/ prefix.
            PathMatcher filenameFallback = null;
            if (pattern.startsWith("**/")) {
                String stripped = pattern.substring(3);
                filenameFallback = FileSystems.getDefault().getPathMatcher("glob:" + stripped);
            }

            final PathMatcher fallback = filenameFallback;
            List<String> matches = new ArrayList<>();

            Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (dirName.startsWith(".") || dirName.equals("target") || dirName.equals("build")
                            || dirName.equals("node_modules") || dirName.equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) return FileVisitResult.TERMINATE;

                    Path relative = rootDir.relativize(file);
                    String relativeStr = relative.toString().replace('\\', '/');

                    if (isSensitiveFile(relativeStr)) return FileVisitResult.CONTINUE;

                    if (matcher.matches(relative) || matcher.matches(file.getFileName())
                            || (fallback != null && fallback.matches(file.getFileName()))) {
                        matches.add(relativeStr);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) {
                ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
                return "No files found matching pattern: " + pattern;
            }

            ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(matches.size()).append(" files");
            if (matches.size() >= maxResults) {
                sb.append(" (limited to ").append(maxResults).append(")");
            }
            sb.append(":\n");
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
            String regex = exclude.replace(".", "\\.").replace("*", ".*");
            if (fileName.matches(regex) || lower.matches(".*" + regex + ".*")) {
                return true;
            }
        }
        return false;
    }
}
