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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Discovery tool: search file contents by regex within a project root.
 * Returns up to 5 matches, each with ±3 lines of context.
 * Path boundary enforced — cannot escape root directory.
 * Sensitive files are excluded.
 */
public class CodeGrepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CodeGrepTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CONTEXT_LINES = 7;

    private static final Set<String> DEFAULT_EXCLUDES = Set.of(
            ".env*", "*.pem", "*.key", "*credentials*", "*secret*",
            "application*.yml", "application*.yaml", "*.jks", "*.p12"
    );

    private final Path rootDir;
    private final Set<String> excludePatterns;
    private final int maxResults;

    public CodeGrepTool(Path rootDir, Set<String> additionalExcludes) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        Set<String> allExcludes = new java.util.HashSet<>(DEFAULT_EXCLUDES);
        if (additionalExcludes != null) allExcludes.addAll(additionalExcludes);
        this.excludePatterns = allExcludes;
        this.maxResults = EnvConfig.get().getInt(EnvKey.TOOL_MAX_RESULTS, 100);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "code_grep",
                "Search file contents by regex pattern within the project directory. " +
                "Returns up to " + maxResults + " matches with ±7 lines of context. " +
                "Use to find API route annotations, controller methods, request/response mappings, etc.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("regex",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Regex pattern to search for (e.g. '@GetMapping|@PostMapping', 'app\\.get\\(')"))
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("glob",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Optional file glob filter (e.g. '*.java', '*.{js,ts}')")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("regex"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String regex = arguments.has("regex") ? arguments.get("regex").asText().trim() : null;
        if (regex == null || regex.isEmpty()) {
            return "ERROR: 'regex' is required";
        }

        // Project not initialized — rootDir is still the default "."
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        if (rootDir.equals(cwd)) {
            throw new ToolExecutionException("code_grep",
                    "Project path not configured. Initialize via project discovery scan first.");
        }

        String fileGlob = arguments.has("glob") ? arguments.get("glob").asText().trim() : null;

        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return "ERROR: Invalid regex: " + e.getMessage();
        }

        log.debug("[CodeGrep] Searching: regex='{}', glob='{}' in {}", regex, fileGlob, rootDir);

        PathMatcher fileMatcher = null;
        if (fileGlob != null && !fileGlob.isEmpty()) {
            fileMatcher = FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
        }

        List<String> results = new ArrayList<>();
        final PathMatcher fm = fileMatcher;

        try {
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
                    if (results.size() >= maxResults) return FileVisitResult.TERMINATE;

                    Path relative = rootDir.relativize(file);
                    String relativeStr = relative.toString().replace('\\', '/');

                    // Sensitive file exclusion
                    if (isSensitiveFile(relativeStr)) return FileVisitResult.CONTINUE;

                    // File glob filter
                    if (fm != null && !fm.matches(relative) && !fm.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }

                    // Only search text files (by extension)
                    String name = file.getFileName().toString().toLowerCase();
                    if (!isTextFile(name)) return FileVisitResult.CONTINUE;

                    try {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            Matcher m = pattern.matcher(lines.get(i));
                            if (m.find()) {
                                int start = Math.max(0, i - CONTEXT_LINES);
                                int end = Math.min(lines.size() - 1, i + CONTEXT_LINES);
                                StringBuilder match = new StringBuilder();
                                match.append("--- ").append(relativeStr).append(":").append(i + 1).append(" ---\n");
                                for (int j = start; j <= end; j++) {
                                    String marker = (j == i) ? ">>>" : "   ";
                                    match.append(marker).append(" ").append(j + 1).append(": ").append(lines.get(j)).append("\n");
                                }
                                results.add(match.toString());
                            }
                        }
                    } catch (IOException e) {
                        // Skip unreadable files
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("[CodeGrep] IO error: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }

        if (results.isEmpty()) {
            ToolResult.setCurrentStatus(ToolResult.ResultStatus.EMPTY);
            return "No matches found for regex: " + regex;
        }

        ToolResult.setCurrentStatus(ToolResult.ResultStatus.SUCCESS);
        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" match(es)");
        if (results.size() >= maxResults) {
            sb.append(" (limited to ").append(maxResults).append(")");
        }
        sb.append(":\n\n");
        for (String r : results) {
            sb.append(r).append("\n");
        }
        return sb.toString();
    }

    private boolean isSensitiveFile(String relativePath) {
        String fileName = Path.of(relativePath).getFileName().toString().toLowerCase();
        for (String exclude : excludePatterns) {
            String regex = exclude.replace(".", "\\.").replace("*", ".*");
            if (fileName.matches(regex)) return true;
        }
        return false;
    }

    private boolean isTextFile(String name) {
        return name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".js")
                || name.endsWith(".ts") || name.endsWith(".jsx") || name.endsWith(".tsx")
                || name.endsWith(".go") || name.endsWith(".rs") || name.endsWith(".rb")
                || name.endsWith(".php") || name.endsWith(".cs") || name.endsWith(".kt")
                || name.endsWith(".scala") || name.endsWith(".swift") || name.endsWith(".c")
                || name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".hpp")
                || name.endsWith(".xml") || name.endsWith(".json") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".toml") || name.endsWith(".md")
                || name.endsWith(".txt") || name.endsWith(".sql") || name.endsWith(".sh")
                || name.endsWith(".bat") || name.endsWith(".properties") || name.endsWith(".gradle")
                || name.endsWith(".vue") || name.endsWith(".svelte") || name.endsWith(".dart");
    }
}
