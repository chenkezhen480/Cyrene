package com.harness.tool.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Discovery tool: read a file's content within the project root.
 * Path boundary enforced — cannot escape root directory.
 * Sensitive files are blocked from reading.
 */
public class CodeReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CodeReadTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_CHARS = 8000;

    private static final Set<String> DEFAULT_EXCLUDES = Set.of(
            ".env*", "*.pem", "*.key", "*credentials*", "*secret*",
            "application*.yml", "application*.yaml", "*.jks", "*.p12", "*.keystore"
    );

    private final Path rootDir;
    private final Set<String> excludePatterns;

    public CodeReadTool(Path rootDir, Set<String> additionalExcludes) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        Set<String> allExcludes = new java.util.HashSet<>(DEFAULT_EXCLUDES);
        if (additionalExcludes != null) allExcludes.addAll(additionalExcludes);
        this.excludePatterns = allExcludes;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "code_read",
                "Read a file's content within the project root. " +
                "Use to examine method signatures, DTO definitions, route registrations, etc. " +
                "Returns up to 8000 characters. Sensitive files (.env, *.pem, *.key) are blocked.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("path",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Relative path from project root (e.g. 'src/main/java/com/example/OrderController.java')"))
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("lines",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Optional line range 'start-end' (e.g. '1-100'). Reads entire file if omitted.")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("path"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String relativePath = arguments.has("path") ? arguments.get("path").asText().trim() : null;
        if (relativePath == null || relativePath.isEmpty()) {
            return "ERROR: 'path' is required";
        }

        // Path boundary check
        Path resolved = rootDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootDir)) {
            log.warn("[CodeRead] Path traversal attempt blocked: {}", relativePath);
            return "ERROR: Path escapes project root boundary";
        }

        // Sensitive file check
        if (isSensitiveFile(relativePath)) {
            log.warn("[CodeRead] Sensitive file blocked: {}", relativePath);
            return "ERROR: Access to sensitive files is blocked (.env, *.pem, *.key, etc.)";
        }

        if (!Files.exists(resolved)) {
            return "ERROR: File not found: " + relativePath;
        }
        if (!Files.isRegularFile(resolved)) {
            return "ERROR: Not a regular file: " + relativePath;
        }

        // Optional line range
        Integer startLine = null, endLine = null;
        if (arguments.has("lines")) {
            String linesStr = arguments.get("lines").asText().trim();
            String[] parts = linesStr.split("-");
            if (parts.length == 2) {
                try {
                    startLine = Integer.parseInt(parts[0].trim());
                    endLine = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    return "ERROR: Invalid lines format, expected 'start-end' (e.g. '1-100')";
                }
            }
        }

        log.debug("[CodeRead] Reading: {} (lines={})", relativePath,
                startLine != null ? startLine + "-" + endLine : "all");

        try {
            List<String> allLines = Files.readAllLines(resolved);
            int from = startLine != null ? Math.max(0, startLine - 1) : 0;
            int to = endLine != null ? Math.min(allLines.size(), endLine) : allLines.size();

            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(relativePath);
            if (startLine != null) {
                sb.append(" (lines ").append(startLine).append("-").append(endLine).append(")");
            }
            sb.append(" (").append(allLines.size()).append(" lines total)\n\n");

            int charCount = 0;
            for (int i = from; i < to; i++) {
                String line = allLines.get(i);
                if (charCount + line.length() + 1 > MAX_CHARS) {
                    sb.append("\n... (truncated at ").append(i).append(" lines, use 'lines' param for specific range)");
                    break;
                }
                sb.append(i + 1).append(": ").append(line).append("\n");
                charCount += line.length() + 1;
            }
            return sb.toString();

        } catch (IOException e) {
            log.error("[CodeRead] IO error reading {}: {}", relativePath, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    private boolean isSensitiveFile(String relativePath) {
        String fileName = Path.of(relativePath).getFileName().toString().toLowerCase();
        for (String exclude : excludePatterns) {
            String regex = exclude.replace(".", "\\.").replace("*", ".*");
            if (fileName.matches(regex)) return true;
        }
        return false;
    }
}
